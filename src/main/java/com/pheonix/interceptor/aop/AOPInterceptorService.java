package com.pheonix.interceptor.aop;

import com.pheonix.interceptor.config.InterceptorProperties;
import com.pheonix.interceptor.kafka.KafkaMessageController;
import com.pheonix.interceptor.model.*;
import com.pheonix.interceptor.model.HostMetrics;
import com.pheonix.interceptor.service.JwtExtractorService;
import com.pheonix.interceptor.service.SystemMetricsService;

import jakarta.annotation.PreDestroy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Aspect
@Component
public class AOPInterceptorService {

    private static final Logger log = LoggerFactory.getLogger(AOPInterceptorService.class);

    /**
     * Guards against duplicate events when an intercepted method calls other
     * intercepted methods on the same thread (Controller → Service → Repository).
     * Only the outermost entry point fires an event.
     */
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    /** Sentinel used in agent fields when no HTTP / JWT context is present. */
    private static final String SYSTEM_AGENT = "system";

    /**
     * Background executor for payload assembly, system-metrics collection,
     * serialization, and Kafka send — keeping those off the request thread.
     *
     * Sizing:
     *   corePoolSize  = 2  — always-on threads for steady traffic
     *   maxPoolSize   = 8  — burst headroom
     *   queue         = 512 bounded slots — back-pressure without unbounded growth
     *   CallerRunsPolicy — if the queue is full the calling thread publishes
     *                      synchronously instead of dropping the event
     */
    private final ThreadPoolExecutor metricsExecutor = new ThreadPoolExecutor(
            2, 8,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512),
            r -> {
                Thread t = new Thread(r, "interceptor-metrics");
                t.setDaemon(true);   // won't block JVM shutdown
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()  // fallback: publish on caller thread
    );

    private final InterceptorProperties   props;
    private final SystemMetricsService    systemMetrics;
    private final JwtExtractorService     jwtExtractor;
    private final KafkaMessageController  kafkaController;

    // ── Application-level rolling counters ───────────────────────────────────
    private final AtomicInteger requestCounter  = new AtomicInteger(0);
    private final AtomicInteger errorCounter    = new AtomicInteger(0);
    private final AtomicLong    totalResponseMs = new AtomicLong(0);
    private volatile long windowStartMs = System.currentTimeMillis();

    public AOPInterceptorService(
            InterceptorProperties  props,
            SystemMetricsService   systemMetrics,
            JwtExtractorService    jwtExtractor,
            KafkaMessageController kafkaController) {
        this.props           = props;
        this.systemMetrics   = systemMetrics;
        this.jwtExtractor    = jwtExtractor;
        this.kafkaController = kafkaController;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pointcut: every Spring-managed bean in the consuming application.
    // Covers @RestController, @Service, @Repository, @Component.
    // The interceptor's own package is excluded to prevent self-interception.
    // ─────────────────────────────────────────────────────────────────────────

    @Around(
        "(@within(org.springframework.web.bind.annotation.RestController) || " +
        " @within(org.springframework.stereotype.Service)                 || " +
        " @within(org.springframework.stereotype.Repository)              || " +
        " @within(org.springframework.stereotype.Component))              && " +
        "!within(com.pheonix.interceptor..*)"
    )
    public Object interceptAll(ProceedingJoinPoint pjp) throws Throwable {

        // Inner call guard — only the outermost intercepted call on this thread publishes.
        if (ACTIVE.get()) {
            return pjp.proceed();
        }

        ACTIVE.set(true);
        long startNs  = System.nanoTime();
        boolean isError = false;

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            isError = true;
            throw t;
        } finally {
            // ── Snapshot everything that is tied to this thread RIGHT NOW ────
            // authHeader lives in a ThreadLocal (RequestContextHolder) and will
            // be gone once the request thread moves on, so it must be read here.
            final long   elapsedMs  = (System.nanoTime() - startNs) / 1_000_000L;
            final boolean errored   = isError;
            final String authHeader = resolveAuthorizationHeader();

            // Atomic counter updates are nanosecond-cheap — do them inline.
            updateWindowCounters(elapsedMs, errored);

            // ── Hand the rest off to the background executor ─────────────────
            // Payload building, system-metrics collection (/proc reads, MXBeans),
            // JSON serialization, and Kafka send all happen off the request thread.
            metricsExecutor.execute(() -> publishEvent(elapsedMs, errored, authHeader));

            ACTIVE.remove(); // always clean up to avoid ThreadLocal leaks
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event assembly & publishing  (runs on executor thread)
    // ─────────────────────────────────────────────────────────────────────────

    private void publishEvent(long responseTimeMs, boolean isError, String authHeader) {
        try {
            HostMetrics host = systemMetrics.getLatest();  // single lock-free cache read

            EventPayload payload = new EventPayload();
            payload.setEventId(UUID.randomUUID().toString());
            payload.setCompany(buildCompany());
            payload.setAgent(buildAgent(authHeader));
            payload.setResource(buildResource());
            payload.setCompute(host.getCompute());
            payload.setMemory(host.getMemory());
            payload.setNetwork(host.getNetwork());
            payload.setDisk(host.getDisk());
            payload.setDatabase(buildDatabase());
            payload.setApplication(buildApplication(responseTimeMs, isError));

            kafkaController.sendMessage(payload);

        } catch (Exception e) {
            log.error("AOPInterceptorService: failed to publish metrics event", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section builders
    // ─────────────────────────────────────────────────────────────────────────

    private Company buildCompany() {
        return new Company(props.getCompanyName());
    }

    /**
     * Builds the Agent section.
     * authHeader is captured on the request thread and passed in explicitly
     * because RequestContextHolder is ThreadLocal and is not available here.
     *
     * Falls back to {@value #SYSTEM_AGENT} when there is no HTTP context
     * (scheduled tasks, async threads, internal system calls).
     */
    private Agent buildAgent(String authHeader) {
        Agent agent = new Agent();

        if (authHeader != null) {
            agent.setAgentId(jwtExtractor.extractAgentId(authHeader));
        } else {
            agent.setAgentId(SYSTEM_AGENT);
        }

        agent.setHostname(resolveHostname());
        agent.setIpAddress(resolveIpAddress());
        return agent;
    }

    private Resource buildResource() {
        Resource resource = new Resource();
        resource.setResourceType(props.getResourceType());
        resource.setResourceId(props.getResourceId());
        resource.setEnvironment(props.getEnvironment());
        resource.setRegion(props.getRegion());
        resource.setAvailabilityZone(props.getAvailabilityZone());
        return resource;
    }

    /**
     * Database section — intentional placeholder.
     * Integrate with your DataSource / HikariCP MXBean for live stats.
     */
    private Database buildDatabase() {
        return new Database();
    }

    private Application buildApplication(long responseTimeMs, boolean isError) {
        Application app = new Application();
        int total  = requestCounter.get();
        int errors = errorCounter.get();
        long windowMs  = System.currentTimeMillis() - windowStartMs;
        double minutes = windowMs / 60_000.0;

        app.setRequestsPerMinute(minutes > 0 ? (int) (total / minutes) : total);
        app.setErrorRatePercent(total > 0
                ? Math.round((double) errors / total * 100 * 100.0) / 100.0
                : 0.0);
        app.setResponseTimeMs(responseTimeMs);
        return app;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rolling window helpers  (called on request thread — atomic ops only)
    // ─────────────────────────────────────────────────────────────────────────

    private void updateWindowCounters(long responseTimeMs, boolean isError) {
        long now = System.currentTimeMillis();
        if (now - windowStartMs > 60_000L) {
            windowStartMs = now;
            requestCounter.set(0);
            errorCounter.set(0);
            totalResponseMs.set(0);
        }
        requestCounter.incrementAndGet();
        if (isError) errorCounter.incrementAndGet();
        totalResponseMs.addAndGet(responseTimeMs);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Must be called on the request thread — RequestContextHolder is ThreadLocal. */
    private String resolveAuthorizationHeader() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            return attrs.getRequest().getHeader("Authorization");
        } catch (Exception e) {
            log.warn("Could not retrieve Authorization header: {}", e.getMessage());
            return null;
        }
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String resolveIpAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "0.0.0.0";
        }
    }

    /** Graceful shutdown — flush queued events before the application exits. */
    @PreDestroy
    public void shutdown() {
        metricsExecutor.shutdown();
        try {
            if (!metricsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("AOPInterceptorService: metrics executor did not terminate cleanly");
                metricsExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            metricsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
