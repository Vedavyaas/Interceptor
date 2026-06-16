package com.pheonix.interceptor.aop;

import com.pheonix.interceptor.config.InterceptorProperties;
import com.pheonix.interceptor.kafka.KafkaMessageController;
import com.pheonix.interceptor.model.*;
import com.pheonix.interceptor.model.HostMetrics;
import com.pheonix.interceptor.service.JwtExtractorService;
import com.pheonix.interceptor.service.SystemMetricsService;
import com.pheonix.interceptor.service.ThreadStatReader;

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
    private final ThreadStatReader        threadStatReader;

    // ── Application-level rolling counters ───────────────────────────────────
    private final AtomicInteger requestCounter  = new AtomicInteger(0);
    private final AtomicInteger errorCounter    = new AtomicInteger(0);
    private final AtomicLong    totalResponseMs = new AtomicLong(0);
    private volatile long windowStartMs = System.currentTimeMillis();

    public AOPInterceptorService(
            InterceptorProperties  props,
            SystemMetricsService   systemMetrics,
            JwtExtractorService    jwtExtractor,
            KafkaMessageController kafkaController,
            ThreadStatReader       threadStatReader) {
        this.props            = props;
        this.systemMetrics    = systemMetrics;
        this.jwtExtractor     = jwtExtractor;
        this.kafkaController  = kafkaController;
        this.threadStatReader = threadStatReader;
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

        // ── Capture the OS TID of the request thread + before-snapshot ──────────
        // resolveTid()  : lists /proc/self/task once (kernel page-cache, ~10 µs)
        // readStat(tid) : reads one /proc file           (~5 µs)
        // Both happen on the request thread so the before-snapshot is accurate.
        // The TID is then handed to the background executor which takes the
        // after-snapshot itself — keeping all remaining /proc I/O off the hot path.
        final long   tid        = threadStatReader.resolveTid();
        final long[] statBefore = threadStatReader.readStat(tid);

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            isError = true;
            throw t;
        } finally {
            // ── Snapshot ThreadLocals before the request thread moves on ─────
            // authHeader lives in RequestContextHolder (ThreadLocal) and will be
            // gone the moment this thread starts handling the next request.
            final long    elapsedMs  = (System.nanoTime() - startNs) / 1_000_000L;
            final boolean errored    = isError;
            final String  authHeader = resolveAuthorizationHeader();

            // Atomic counter updates are nanosecond-cheap — do them inline.
            updateWindowCounters(elapsedMs, errored);

            // ── Hand tid + statBefore off to the background executor ──────────
            // The background thread will:
            //   1. read statAfter via the same tid  (/proc/pid/task/tid/stat)
            //   2. compute the delta (utime+stime jiffies → CPU %)
            //   3. assemble and Kafka-publish the full EventPayload
            // tid and statBefore are primitives/array — safe across threads.
            metricsExecutor.execute(() -> publishEvent(elapsedMs, errored, authHeader,
                                                       tid, statBefore));

            ACTIVE.remove(); // always clean up to avoid ThreadLocal leaks
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event assembly & publishing  (runs on executor thread)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs entirely on the background executor thread.
     *
     * <p>Receives the OS TID captured by the request thread and immediately
     * reads {@code /proc/&lt;pid&gt;/task/&lt;tid&gt;/stat} for the after-snapshot.
     * The delta between {@code statBefore} (captured pre-{@code pjp.proceed()})
     * and {@code statAfter} (captured here, post-request) represents the CPU
     * jiffies consumed by that OS thread during the request window.
     *
     * @param responseTimeMs wall-clock duration of the request
     * @param isError        whether the request threw an exception
     * @param authHeader     Authorization header value (captured on request thread)
     * @param tid            OS thread-ID resolved on the request thread
     * @param statBefore     /proc stat snapshot taken on request thread BEFORE proceed
     */
    private void publishEvent(long responseTimeMs, boolean isError, String authHeader,
                              long tid, long[] statBefore) {
        try {
            // ── After-snapshot read happens here, on the background thread ────
            // The TID was captured on the request thread and handed to us;
            // we read /proc/pid/task/tid/stat now to compute the jiffy delta.
            final long[] statAfter = threadStatReader.readStat(tid);

            HostMetrics host = systemMetrics.getLatest();  // single lock-free cache read

            EventPayload payload = new EventPayload();
            payload.setEventId(UUID.randomUUID().toString());
            payload.setCompany(buildCompany());
            payload.setAgent(buildAgent(authHeader));
            payload.setResource(buildResource());
            // Compute: cpuUsagePercent → per-thread delta from /proc/pid/task/tid/stat
            payload.setCompute(buildCompute(host.getCompute(), statBefore, statAfter, responseTimeMs));
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

    /**
     * Builds the Compute section.
     *
     * <p>When {@code /proc} is available, {@code cpuUsagePercent} is set to the
     * CPU percentage consumed by the request's own OS thread (delta of
     * utime+stime jiffies ÷ wall-clock seconds). All other fields (cores, load
     * averages) stay from the background-cached host snapshot.
     * The Kafka payload shape is unchanged.
     *
     * @param hostCompute  background-cached host-level Compute snapshot
     * @param statBefore   stat snapshot captured on request thread BEFORE proceed
     * @param statAfter    stat snapshot captured on background thread AFTER proceed
     * @param wallClockMs  wall-clock duration of the request in milliseconds
     */
    private Compute buildCompute(Compute hostCompute,
                                 long[] statBefore, long[] statAfter,
                                 long wallClockMs) {
        Double threadCpuPercent =
                threadStatReader.computeCpuPercent(statBefore, statAfter, wallClockMs);

        if (threadCpuPercent != null) {
            // cpuUsagePercent → precise per-thread value from /proc delta
            // everything else  → kept from host-level cache (unchanged payload shape)
            Compute c = new Compute();
            c.setCpuUsagePercent(threadCpuPercent);
            c.setCpuCores(hostCompute.getCpuCores());
            c.setLoadAverage1m (hostCompute.getLoadAverage1m());
            c.setLoadAverage5m (hostCompute.getLoadAverage5m());
            c.setLoadAverage15m(hostCompute.getLoadAverage15m());
            return c;
        }

        // /proc not available (macOS, Windows) — fall back to the cached host snapshot.
        return hostCompute;
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
