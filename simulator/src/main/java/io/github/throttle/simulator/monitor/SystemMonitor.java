package io.github.throttle.simulator.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors system resources (CPU, Memory) and records metrics.
 */
public class SystemMonitor {
    private static final Logger log = LoggerFactory.getLogger(SystemMonitor.class);

    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;
    private final List<SystemSnapshot> snapshots = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean monitoring = false;

    public SystemMonitor() {
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    /**
     * Start monitoring system resources.
     *
     * @param intervalMs Sampling interval in milliseconds
     */
    public void startMonitoring(long intervalMs) {
        if (monitoring) {
            log.warn("Already monitoring");
            return;
        }

        monitoring = true;
        snapshots.clear();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "System-Monitor");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                SystemSnapshot snapshot = captureSnapshot();
                snapshots.add(snapshot);
                log.debug("CPU: {}%, Memory: {}%",
                    snapshot.getCpuUsage(), snapshot.getMemoryUsage());
            } catch (Exception e) {
                log.error("Error capturing system snapshot", e);
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        log.info("Started system monitoring (interval={}ms)", intervalMs);
    }

    /**
     * Stop monitoring.
     */
    public void stopMonitoring() {
        if (!monitoring) {
            return;
        }

        monitoring = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Stopped system monitoring");
    }

    /**
     * Capture current system state.
     */
    private SystemSnapshot captureSnapshot() {
        double cpuLoad = getSystemCpuLoad();
        double memoryUsage = getHeapMemoryUsagePercent();
        long timestamp = System.currentTimeMillis();

        return new SystemSnapshot(timestamp, cpuLoad, memoryUsage);
    }

    /**
     * Get system CPU load as percentage.
     */
    private double getSystemCpuLoad() {
        double load = osMxBean.getSystemLoadAverage();
        int processors = osMxBean.getAvailableProcessors();

        if (load < 0) {
            return 0.0;
        }

        return (load / processors) * 100.0;
    }

    /**
     * Get heap memory usage as percentage.
     */
    private double getHeapMemoryUsagePercent() {
        MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        return (double) used / max * 100.0;
    }

    /**
     * Get all captured snapshots.
     */
    public List<SystemSnapshot> getSnapshots() {
        return new ArrayList<>(snapshots);
    }

    /**
     * Get monitoring statistics.
     */
    public MonitoringStats getStats() {
        if (snapshots.isEmpty()) {
            return new MonitoringStats(0, 0, 0, 0, 0, 0, 0);
        }

        double minCpu = Double.MAX_VALUE;
        double maxCpu = 0;
        double avgCpu = 0;

        double minMemory = Double.MAX_VALUE;
        double maxMemory = 0;
        double avgMemory = 0;

        for (SystemSnapshot snapshot : snapshots) {
            minCpu = Math.min(minCpu, snapshot.getCpuUsage());
            maxCpu = Math.max(maxCpu, snapshot.getCpuUsage());
            avgCpu += snapshot.getCpuUsage();

            minMemory = Math.min(minMemory, snapshot.getMemoryUsage());
            maxMemory = Math.max(maxMemory, snapshot.getMemoryUsage());
            avgMemory += snapshot.getMemoryUsage();
        }

        int count = snapshots.size();
        avgCpu /= count;
        avgMemory /= count;

        return new MonitoringStats(count, minCpu, maxCpu, avgCpu, minMemory, maxMemory, avgMemory);
    }

    /**
     * Clear all snapshots.
     */
    public void clearSnapshots() {
        snapshots.clear();
    }
}

