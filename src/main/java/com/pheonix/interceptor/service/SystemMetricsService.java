package com.pheonix.interceptor.service;

import com.pheonix.interceptor.model.Compute;
import com.pheonix.interceptor.model.Disk;
import com.pheonix.interceptor.model.HostMetrics;
import com.pheonix.interceptor.model.Memory;
import com.pheonix.interceptor.model.Network;
import com.sun.management.OperatingSystemMXBean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects host-level metrics (CPU, memory, network, disk) on a background
 * thread every {@value #REFRESH_INTERVAL_SEC} seconds and stores the result
 * in an {@link AtomicReference}.
 *
 * <p>The AOP layer calls {@link #getLatest()} which is a single
 * lock-free cache read — zero filesystem or MXBean access on the hot path.
 */
@Service
public class SystemMetricsService {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsService.class);

    /** How often the background thread refreshes the metrics snapshot (seconds). */
    private static final int REFRESH_INTERVAL_SEC = 5;

    private static final double MB = 1024.0 * 1024.0;
    private static final double GB = 1024.0 * 1024.0 * 1024.0;

    private final OperatingSystemMXBean osMxBean;

    // ── /proc delta tracking ──────────────────────────────────────────────────
    // /proc/net/dev and /proc/diskstats are cumulative since boot.
    // We store the last raw reading so each snapshot reports activity
    // since the previous collection interval, not since machine startup.
    private long lastNetRxBytes    = 0;
    private long lastNetTxBytes    = 0;
    private long lastDiskReadBytes  = 0;
    private long lastDiskWriteBytes = 0;

    /** Latest metrics snapshot — updated by the scheduler, read by the AOP layer. */
    private final AtomicReference<HostMetrics> cache = new AtomicReference<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "interceptor-metrics-collector");
        t.setDaemon(true);  // won't block JVM shutdown
        return t;
    });

    public SystemMetricsService() {
        this.osMxBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // Warm up /proc baselines so the first delta is meaningful
        long[] net  = readProcNetDev();
        long[] disk = readProcDiskStats();
        lastNetRxBytes    = net[0];
        lastNetTxBytes    = net[1];
        lastDiskReadBytes  = disk[0];
        lastDiskWriteBytes = disk[1];

        // Populate cache immediately so getLatest() never returns null
        cache.set(collect());

        // Refresh every REFRESH_INTERVAL_SEC seconds with a fixed delay
        // (fixed delay = next run starts AFTER previous run completes,
        //  so overlapping reads are impossible even if collection is slow)
        scheduler.scheduleWithFixedDelay(
                this::refresh,
                REFRESH_INTERVAL_SEC,
                REFRESH_INTERVAL_SEC,
                TimeUnit.SECONDS
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called by AOP, zero I/O
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the most recently collected {@link HostMetrics} snapshot.
     * This is a single {@link AtomicReference#get()} — no filesystem access,
     * no MXBean calls, no locking.
     */
    public HostMetrics getLatest() {
        return cache.get();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Background collection — runs every REFRESH_INTERVAL_SEC seconds
    // ─────────────────────────────────────────────────────────────────────────

    private void refresh() {
        try {
            cache.set(collect());
        } catch (Exception e) {
            log.warn("SystemMetricsService: metrics refresh failed", e);
            // Keep the stale snapshot rather than clearing it
        }
    }

    private HostMetrics collect() {
        return new HostMetrics(
                collectCompute(),
                collectMemory(),
                collectNetwork(),
                collectDisk()
        );
    }

    // ── Compute ──────────────────────────────────────────────────────────────

    private Compute collectCompute() {
        Compute compute = new Compute();

        double cpuLoad = osMxBean.getCpuLoad();
        // getCpuLoad() returns -1.0 when unavailable — use null so consumers
        // can distinguish "genuinely 0% CPU" from "data not available".
        compute.setCpuUsagePercent(cpuLoad < 0 ? null : round2(cpuLoad * 100));
        compute.setCpuCores(osMxBean.getAvailableProcessors());

        double[] loadAvg = loadAverages();
        compute.setLoadAverage1m (loadAvg[0] < 0 ? null : round2(loadAvg[0]));
        compute.setLoadAverage5m (loadAvg[1] < 0 ? null : round2(loadAvg[1]));
        compute.setLoadAverage15m(loadAvg[2] < 0 ? null : round2(loadAvg[2]));

        return compute;
    }

    /**
     * Reads real [1m, 5m, 15m] load averages from {@code /proc/loadavg} on Linux.
     * Falls back to the JVM 1-minute figure (with -1 for 5m/15m) on non-Linux hosts.
     */
    private double[] loadAverages() {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("/proc/loadavg");
            if (java.nio.file.Files.exists(path)) {
                String line = java.nio.file.Files.readAllLines(path).get(0);
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) {
                    return new double[]{
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2])
                    };
                }
            }
        } catch (Exception ignored) { /* fall through */ }

        double load1m = osMxBean.getSystemLoadAverage();
        return new double[]{load1m < 0 ? -1 : load1m, -1, -1};
    }

    // ── Memory ───────────────────────────────────────────────────────────────

    private Memory collectMemory() {
        Memory memory = new Memory();

        long totalBytes = osMxBean.getTotalMemorySize();
        long freeBytes  = osMxBean.getFreeMemorySize();
        long usedBytes  = totalBytes - freeBytes;

        memory.setTotalMB(totalBytes / (long) MB);
        memory.setFreeMB (freeBytes  / (long) MB);
        memory.setUsedMB (usedBytes  / (long) MB);
        memory.setUsagePercent(totalBytes > 0
                ? round2((double) usedBytes / totalBytes * 100)
                : 0.0);

        return memory;
    }

    // ── Network ──────────────────────────────────────────────────────────────

    private Network collectNetwork() {
        Network network = new Network();

        int activeConns = 0;
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isLoopback() && iface.isUp()) {
                    activeConns += iface.getInterfaceAddresses().size();
                }
            }
        } catch (Exception ignored) { }

        // Delta since last collection — /proc/net/dev is cumulative since boot
        long[] netIo   = readProcNetDev();
        long   deltaRx = Math.max(0, netIo[0] - lastNetRxBytes);
        long   deltaTx = Math.max(0, netIo[1] - lastNetTxBytes);
        lastNetRxBytes = netIo[0];
        lastNetTxBytes = netIo[1];

        network.setNetworkInMB(round2(deltaRx / MB));
        network.setNetworkOutMB(round2(deltaTx / MB));
        network.setActiveConnections(activeConns);

        return network;
    }

    /** Reads cumulative RX/TX bytes from {@code /proc/net/dev} on Linux. */
    private long[] readProcNetDev() {
        long rx = 0, tx = 0;
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("/proc/net/dev");
            if (java.nio.file.Files.exists(path)) {
                for (String line : java.nio.file.Files.readAllLines(path)) {
                    line = line.trim();
                    if (line.startsWith("lo:") || !line.contains(":")) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 10) {
                        rx += Long.parseLong(parts[1]);
                        tx += Long.parseLong(parts[9]);
                    }
                }
            }
        } catch (Exception ignored) { }
        return new long[]{rx, tx};
    }

    // ── Disk ─────────────────────────────────────────────────────────────────

    private Disk collectDisk() {
        Disk disk = new Disk();

        File root       = new File("/");
        long totalSpace = root.getTotalSpace();
        long usedSpace  = totalSpace - root.getFreeSpace();

        disk.setDiskUsagePercent(totalSpace > 0
                ? round2((double) usedSpace / totalSpace * 100)
                : 0.0);
        disk.setStorageUsedGB(round2(usedSpace / GB));

        // Delta since last collection — /proc/diskstats is cumulative since boot
        long[] diskIo      = readProcDiskStats();
        long   deltaRead   = Math.max(0, diskIo[0] - lastDiskReadBytes);
        long   deltaWrite  = Math.max(0, diskIo[1] - lastDiskWriteBytes);
        lastDiskReadBytes  = diskIo[0];
        lastDiskWriteBytes = diskIo[1];

        disk.setDiskReadMB (round2(deltaRead  / MB));
        disk.setDiskWriteMB(round2(deltaWrite / MB));

        return disk;
    }

    /** Reads cumulative sector read/write from {@code /proc/diskstats} on Linux. */
    private long[] readProcDiskStats() {
        long readBytes = 0, writeBytes = 0;
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("/proc/diskstats");
            if (java.nio.file.Files.exists(path)) {
                for (String line : java.nio.file.Files.readAllLines(path)) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 14) {
                        readBytes  += Long.parseLong(parts[5]) * 512L;
                        writeBytes += Long.parseLong(parts[9]) * 512L;
                    }
                }
            }
        } catch (Exception ignored) { }
        return new long[]{readBytes, writeBytes};
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
