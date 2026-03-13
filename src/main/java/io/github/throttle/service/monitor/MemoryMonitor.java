package io.github.throttle.service.monitor;

import io.github.throttle.service.base.MonitorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;

/**
 * Memory resource monitor with hysteresis.
 * Monitors heap memory usage and triggers pause when hot, resume when cold.
 */
public class MemoryMonitor implements ResourceMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryMonitor.class);
    private static final String ID = "memory";

    private final MemoryMXBean memoryMxBean;
    private final double hotThreshold;
    private final double coldThreshold;
    private final Duration hysteresis;
    private MonitorState state = MonitorState.NORMAL;
    private Instant stateChangeTime = Instant.now();
    private Instant breachStartTime;

    public MemoryMonitor(double hotThreshold, double coldThreshold, Duration hysteresis) {
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
        this.hotThreshold = hotThreshold;
        this.coldThreshold = coldThreshold;
        this.hysteresis = hysteresis;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public MonitorState evaluate() {
        double memoryUsage = getHeapMemoryUsagePercent();
        Instant now = Instant.now();

        switch (state) {
            case NORMAL:
                if (memoryUsage > hotThreshold) {
                    breachStartTime = now;
                    LOGGER.debug("Memory usage breaching above hot threshold: {}% (threshold: {}%)", String.format("%.2f", memoryUsage), hotThreshold);
                    changeState(MonitorState.BREACHING, now);
                }
                break;

            case BREACHING:
                if (memoryUsage <= hotThreshold) {
                    // Dropped back to normal
                    breachStartTime = null;
                    LOGGER.debug("Memory usage back to normal from BREACHING: {}% (threshold: {}%)", String.format("%.2f", memoryUsage), hotThreshold);
                    changeState(MonitorState.NORMAL, now);
                } else if (breachStartTime != null &&
                          Duration.between(breachStartTime, now).compareTo(hysteresis) >= 0) {
                    // Sustained breach
                    LOGGER.info("Memory usage hot with sustained breach: {}% (threshold: {}%)", String.format("%.2f", memoryUsage), hotThreshold);
                    changeState(MonitorState.HOT, now);
                    breachStartTime = null;
                }
                break;

            case HOT:
                if (memoryUsage < coldThreshold) {
                    breachStartTime = now;
                    LOGGER.debug("Memory usage breaching below cold threshold: {}% (threshold: {}%)", String.format("%.2f", memoryUsage), coldThreshold);
                    changeState(MonitorState.COOLING, now);
                }
                break;

            case COOLING:
                if (memoryUsage >= coldThreshold) {
                    // Spiked back up
                    breachStartTime = null;
                    LOGGER.debug("Memory usage spiked back above cold threshold during COOLING: {}% (threshold: {}%)", String.format("%.2f", memoryUsage), coldThreshold);
                    changeState(MonitorState.HOT, now);
                } else if (breachStartTime != null &&
                          Duration.between(breachStartTime, now).compareTo(hysteresis) >= 0) {
                    // Sustained cool
                    LOGGER.info("Memory usage back to normal: {}% (threshold: {}%)", String.format("%.2f", memoryUsage), coldThreshold);
                    changeState(MonitorState.NORMAL, now);
                    breachStartTime = null;
                }
                break;
        }

        return state;
    }

    private double getHeapMemoryUsagePercent() {
        MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        return (double) used / max * 100.0;
    }

    private void changeState(MonitorState newState, Instant now) {
        this.state = newState;
        this.stateChangeTime = now;
    }

    @Override
    public MonitorMetrics getMetrics() {
        return new MonitorMetrics(
            ID,
            getHeapMemoryUsagePercent(),
            hotThreshold,
            coldThreshold,
            state,
            Duration.between(stateChangeTime, Instant.now())
        );
    }
}

