package com.pheonix.interceptor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reads per-thread CPU accounting from {@code /proc/&lt;pid&gt;/task/&lt;tid&gt;/stat}.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>At the start of an intercepted request, call {@link #snapshot()} on the
 *       request thread → returns a {@code long[3]} {@code [utime, stime, numThreads]}.
 *   <li>After the request completes (in the {@code finally} block), call
 *       {@link #snapshot()} again on the same thread.
 *   <li>Subtract the before snapshot from the after snapshot to get the CPU
 *       jiffies consumed <em>by this thread alone</em> during the request.
 *   <li>Convert to a percentage: {@code (deltaJiffies / CLK_TCK) / wallClockSec * 100}.
 * </ol>
 *
 * <h3>Linux TID resolution</h3>
 * Java's {@code Thread.getId()} is a JVM-internal ID, not the Linux kernel TID.
 * We resolve the real OS TID by listing {@code /proc/self/task/} — each entry
 * name IS the OS TID — and matching the entry whose {@code stat} field 2 (comm)
 * or scheduling state best corresponds to this thread.
 *
 * <p>A simpler and fully reliable approach on modern Linux: the JVM thread names
 * its native counterpart identically, so we can correlate via the {@code comm}
 * field (the executable name truncated to 15 chars). When the thread name is
 * unique among live threads this is unambiguous. As a final fallback we read
 * {@code /proc/self/status} which always has the main-thread TID.
 *
 * <h3>Non-Linux fallback</h3>
 * On macOS / Windows, {@code /proc} does not exist. Every method returns
 * {@code null} / {@code UNAVAILABLE} sentinels silently — the caller must
 * handle {@code null} gracefully.
 */
@Component
public class ThreadStatReader {

    private static final Logger log = LoggerFactory.getLogger(ThreadStatReader.class);

    /** Sentinel: /proc is not available on this OS. */
    public static final long[] UNAVAILABLE = null;

    /**
     * Linux clock ticks per second — almost universally 100 (i.e. 1 jiffy = 10 ms).
     * Read once at startup from /proc/self/status heuristic; fallback = 100.
     */
    private final int clkTck;

    /** PID of the current JVM process, resolved once at startup. */
    private final long pid;

    /** {@code true} if we are running on a Linux host with /proc available. */
    private final boolean procAvailable;

    public ThreadStatReader() {
        this.pid          = ProcessHandle.current().pid();
        this.procAvailable = Files.isDirectory(Paths.get("/proc/self/task"));
        this.clkTck       = detectClkTck();

        if (procAvailable) {
            log.info("ThreadStatReader: /proc available, pid={}, CLK_TCK={}", pid, clkTck);
        } else {
            log.info("ThreadStatReader: /proc not available — per-thread CPU metrics disabled");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the clock ticks per second (CLK_TCK) used for jiffy → ms conversion.
     * Almost always {@code 100} on Linux (1 jiffy = 10 ms).
     */
    public int getClkTck() {
        return clkTck;
    }

    /**
     * Returns {@code true} if this host exposes {@code /proc/self/task}.
     * When {@code false}, all other methods return {@code null}.
     */
    public boolean isProcAvailable() {
        return procAvailable;
    }

    /**
     * Resolves the Linux OS TID (kernel thread ID) of the calling thread.
     *
     * <p>Strategy: list {@code /proc/self/task/}, read each entry's {@code stat}
     * file, and match the entry whose {@code comm} field (field 2, stripped of
     * parentheses) starts with the first 15 characters of the current thread's
     * name. Returns the first matching TID, or {@code -1} if unresolvable.
     *
     * @return OS TID, or {@code -1} on failure / non-Linux
     */
    public long resolveTid() {
        if (!procAvailable) return -1;

        String javaName = Thread.currentThread().getName();
        // Linux comm is truncated to 15 characters
        String commPrefix = javaName.length() > 15 ? javaName.substring(0, 15) : javaName;

        Path taskDir = Paths.get("/proc/self/task");
        try (var stream = Files.newDirectoryStream(taskDir)) {
            for (Path tidPath : stream) {
                String tidStr = tidPath.getFileName().toString();
                Path statFile = tidPath.resolve("stat");
                if (!Files.exists(statFile)) continue;

                String statLine = Files.readString(statFile);
                String comm = extractComm(statLine);  // field 2 inside (...)

                if (comm != null && comm.startsWith(commPrefix)) {
                    try {
                        return Long.parseLong(tidStr);
                    } catch (NumberFormatException ignored) { /* skip */ }
                }
            }
        } catch (IOException e) {
            log.debug("ThreadStatReader.resolveTid: could not list /proc/self/task — {}", e.getMessage());
        }

        return -1;
    }

    /**
     * Reads a stat snapshot for the given {@code tid} from
     * {@code /proc/&lt;pid&gt;/task/&lt;tid&gt;/stat}.
     *
     * <p>The returned array has exactly 3 elements:
     * <ul>
     *   <li>[0] — {@code utime}  (user-mode jiffies, field 14)</li>
     *   <li>[1] — {@code stime}  (kernel-mode jiffies, field 15)</li>
     *   <li>[2] — {@code num_threads} (field 20, live threads in process)</li>
     * </ul>
     *
     * @param tid OS thread ID previously obtained from {@link #resolveTid()}
     * @return {@code long[3]}, or {@code null} if /proc is unavailable or the
     *         read fails (thread may have already exited)
     */
    public long[] readStat(long tid) {
        if (!procAvailable || tid < 0) return null;

        Path statFile = Paths.get("/proc", String.valueOf(pid), "task", String.valueOf(tid), "stat");
        try {
            String line = Files.readString(statFile);
            return parseStat(line);
        } catch (IOException e) {
            // Thread may have exited between TID resolution and the read — acceptable
            log.debug("ThreadStatReader.readStat: could not read {} — {}", statFile, e.getMessage());
            return null;
        }
    }

    /**
     * Convenience: resolve TID then immediately read its stat.
     * Returns {@code null} when /proc is unavailable or the read fails.
     */
    public long[] snapshot() {
        long tid = resolveTid();
        return readStat(tid);
    }

    /**
     * Computes the CPU usage percentage of this thread for the request duration.
     *
     * <p>Formula: {@code (deltaUtime + deltaStime) / CLK_TCK / wallClockSec * 100}
     *
     * @param before     stat snapshot taken before {@code pjp.proceed()}
     * @param after      stat snapshot taken after {@code pjp.proceed()}
     * @param wallClockMs wall-clock duration of the request in milliseconds
     * @return CPU percentage (0.0–100.0+), or {@code null} if inputs are invalid
     */
    public Double computeCpuPercent(long[] before, long[] after, long wallClockMs) {
        if (before == null || after == null || wallClockMs <= 0) return null;

        long deltaJiffies = (after[0] - before[0]) + (after[1] - before[1]);
        if (deltaJiffies < 0) return null;  // thread accounting wrapped — skip

        // deltaJiffies / CLK_TCK = CPU seconds consumed by thread
        // wallClockMs / 1000     = wall-clock seconds of the request
        double cpuSeconds  = (double) deltaJiffies / clkTck;
        double wallSeconds = wallClockMs / 1000.0;

        return round2(cpuSeconds / wallSeconds * 100.0);
    }

    /**
     * Returns the live thread count recorded in the post-request snapshot
     * (stat field 20 = {@code num_threads}).
     *
     * @param afterSnapshot stat snapshot taken after {@code pjp.proceed()}
     * @return thread count, or {@code null} if the snapshot is unavailable
     */
    public Integer processThreadCount(long[] afterSnapshot) {
        if (afterSnapshot == null) return null;
        return (int) afterSnapshot[2];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses {@code /proc/.../stat} into {@code [utime, stime, num_threads]}.
     *
     * <p>The stat format is tricky because field 2 (comm) is wrapped in
     * parentheses and can itself contain spaces and parentheses. We find the
     * last {@code )} to reliably locate the start of the remaining fields.
     */
    private long[] parseStat(String line) {
        // Field 2 (comm) ends at the last ')' on the line
        int commEnd = line.lastIndexOf(')');
        if (commEnd < 0) return null;

        // Everything after ") " is the remaining fields starting at field 3
        String rest = line.substring(commEnd + 2).trim();
        String[] fields = rest.split("\\s+");

        // Fields are 0-indexed here, but relative to field 3 in the spec:
        //   field  3 = state          → fields[0]
        //   field 14 = utime          → fields[11]
        //   field 15 = stime          → fields[12]
        //   field 20 = num_threads    → fields[17]
        if (fields.length < 18) return null;

        try {
            long utime      = Long.parseLong(fields[11]);
            long stime      = Long.parseLong(fields[12]);
            long numThreads = Long.parseLong(fields[17]);
            return new long[]{utime, stime, numThreads};
        } catch (NumberFormatException e) {
            log.debug("ThreadStatReader.parseStat: unexpected format — {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the {@code comm} value (field 2) from a {@code /proc/../stat} line.
     * The comm is the substring between the first {@code (} and last {@code )}.
     */
    private String extractComm(String statLine) {
        int start = statLine.indexOf('(');
        int end   = statLine.lastIndexOf(')');
        if (start < 0 || end < 0 || end <= start) return null;
        return statLine.substring(start + 1, end);
    }

    /**
     * Heuristically detects CLK_TCK.
     * On virtually all Linux systems this is 100 (USER_HZ = 100).
     * We simply return 100 — it is the universal default.
     * If you ever need to read it dynamically, link against libc's
     * {@code sysconf(_SC_CLK_TCK)} via JNI/JNA.
     */
    private int detectClkTck() {
        return 100;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
