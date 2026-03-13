package io.github.throttle.simulator.monitor;

/**
 * Snapshot of system state at a point in time.
 */
public class SystemSnapshot {
    private final long timestamp;
    private final double cpuUsage;
    private final double memoryUsage;

    public SystemSnapshot(long timestamp, double cpuUsage, double memoryUsage) {
        this.timestamp = timestamp;
        this.cpuUsage = cpuUsage;
        this.memoryUsage = memoryUsage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getCpuUsage() {
        return cpuUsage;
    }

    public double getMemoryUsage() {
        return memoryUsage;
    }

    @Override
    public String toString() {
        return String.format("SystemSnapshot{time=%d, cpu=%.1f%%, memory=%.1f%%}",
            timestamp, cpuUsage, memoryUsage);
    }
}
