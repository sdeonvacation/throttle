package io.github.throttle.simulator.monitor;

/**
 * Statistics from monitoring session.
 */
public class MonitoringStats {
    private final int sampleCount;
    private final double minCpu;
    private final double maxCpu;
    private final double avgCpu;
    private final double minMemory;
    private final double maxMemory;
    private final double avgMemory;

    public MonitoringStats(int sampleCount, double minCpu, double maxCpu, double avgCpu,
                          double minMemory, double maxMemory, double avgMemory) {
        this.sampleCount = sampleCount;
        this.minCpu = minCpu;
        this.maxCpu = maxCpu;
        this.avgCpu = avgCpu;
        this.minMemory = minMemory;
        this.maxMemory = maxMemory;
        this.avgMemory = avgMemory;
    }

    public int getSampleCount() { return sampleCount; }
    public double getMinCpu() { return minCpu; }
    public double getMaxCpu() { return maxCpu; }
    public double getAvgCpu() { return avgCpu; }
    public double getMinMemory() { return minMemory; }
    public double getMaxMemory() { return maxMemory; }
    public double getAvgMemory() { return avgMemory; }

    @Override
    public String toString() {
        return String.format(
            "MonitoringStats{samples=%d, CPU[min=%.1f%%, max=%.1f%%, avg=%.1f%%], " +
            "Memory[min=%.1f%%, max=%.1f%%, avg=%.1f%%]}",
            sampleCount, minCpu, maxCpu, avgCpu, minMemory, maxMemory, avgMemory);
    }
}
