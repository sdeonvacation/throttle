package io.github.throttle.service.monitor;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.throttle.service.base.MonitorMetrics;

/**
 * CPU resource monitor with hysteresis.
 * Monitors system CPU load and triggers pause when hot, resume when cold.
 */
public class CpuMonitor implements ResourceMonitor {
    private static final Logger LOGGER = Logger.getLogger(CpuMonitor.class.getName());
    private static final String ID = "cpu";

    private final OperatingSystemMXBean osMxBean;
    private final double hotThreshold;
    private final double coldThreshold;
    private final Duration hysteresis;
    private MonitorState state = MonitorState.NORMAL;
    private Instant stateChangeTime = Instant.now();

    // Smoothing mechanism to reduce fluctuations
    private double smoothedCpuLoad = 0.0;
    private static final double SMOOTHING_FACTOR = 0.3; // 30% weight to new value, 70% to history
    private boolean isFirstSample = true;

    // Flag to track if CPU monitoring is unavailable (warn once only)
    private boolean cpuMonitoringUnavailable = false;

    public CpuMonitor(double hotThreshold, double coldThreshold, Duration hysteresis) {
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.hotThreshold = hotThreshold;
        this.coldThreshold = coldThreshold;
        this.hysteresis = hysteresis;
        LOGGER.info("CpuMonitor initialized: hotThreshold=" + hotThreshold +
                   ", coldThreshold=" + coldThreshold + ", hysteresis=" + hysteresis);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public MonitorState evaluate() {
        double cpuLoad = getCpuLoad();
        Instant now = Instant.now();

        LOGGER.log(Level.FINE, "Evaluating CPU: load={0}%, state={1}, hotThreshold={2}, coldThreshold={3}",
            new Object[]{String.format("%.2f", cpuLoad), state, hotThreshold, coldThreshold});

        switch (state) {
            case NORMAL:
                evaluateNormalState(cpuLoad, now);
                break;
            case BREACHING:
                evaluateBreachingState(cpuLoad, now);
                break;
            case HOT:
                evaluateHotState(cpuLoad, now);
                break;
            case COOLING:
                evaluateCoolingState(cpuLoad, now);
                break;
        }

        return state;
    }

    /**
     * Handle NORMAL state: Check if CPU exceeds hot threshold.
     */
    private void evaluateNormalState(double cpuLoad, Instant now) {
        if (cpuLoad > hotThreshold) {
            LOGGER.log(Level.FINE, "(evaludateNormalState) CPU BREACHING: {0}% > {1}%", new Object[]{String.format("%.2f", cpuLoad), hotThreshold});
            transitionTo(MonitorState.BREACHING, now);
        }
    }

    /**
     * Handle BREACHING state: Check if sustained breach or dropped back to normal.
     */
    private void evaluateBreachingState(double cpuLoad, Instant now) {
        if (cpuLoad <= hotThreshold) {
            // CPU dropped back below threshold
            LOGGER.info("(evaludateNormalState) CPU dropped below hot threshold, returning to NORMAL");
            transitionTo(MonitorState.NORMAL, now);
            return;
        }

        // Still breaching - check if sustained long enough
        if (isSustained(now)) {
            LOGGER.info("(evaludateNormalState) CPU HOT: Sustained breach for " + getDurationInState(now).toMillis() + "ms");
            transitionTo(MonitorState.HOT, now);
        }
    }

    /**
     * Handle HOT state: Check if CPU dropped below cold threshold.
     */
    private void evaluateHotState(double cpuLoad, Instant now) {
        if (cpuLoad < coldThreshold) {
            LOGGER.log(Level.FINE, "(evaludateHotState) CPU COOLING: {0}% < {1}%", new Object[]{String.format("%.2f", cpuLoad), coldThreshold});
            transitionTo(MonitorState.COOLING, now);
        } else {
            LOGGER.log(Level.FINE, "(evaludateHotState) CPU still HOT: {0}% >= {1}%", new Object[]{String.format("%.2f", cpuLoad), coldThreshold});
        }
    }

    /**
     * Handle COOLING state: Check if sustained cool or spiked back up.
     */
    private void evaluateCoolingState(double cpuLoad, Instant now) {
        if (cpuLoad >= coldThreshold) {
            // CPU spiked back above threshold
            LOGGER.info("(evaludateCoolingState) CPU spiked back up, returning to HOT");
            transitionTo(MonitorState.HOT, now);
            return;
        }

        // Still cooling - check if sustained long enough
        if (isSustained(now)) {
            LOGGER.info("(evaludateCoolingState) CPU NORMAL: Sustained cooling for " + getDurationInState(now).toMillis() + "ms");
            transitionTo(MonitorState.NORMAL, now);
        } else {
            LOGGER.log(Level.FINE, "CPU cooling: {0}%, waiting {1}/{2}ms",
                new Object[]{String.format("%.2f", cpuLoad), getDurationInState(now).toMillis(), hysteresis.toMillis()});
        }
    }

    /**
     * Check if current state has been sustained for hysteresis duration.
     */
    private boolean isSustained(Instant now) {
        return getDurationInState(now).compareTo(hysteresis) >= 0;
    }

    /**
     * Get duration spent in current state.
     */
    private Duration getDurationInState(Instant now) {
        return Duration.between(stateChangeTime, now);
    }

    /**
     * Transition to a new state and update state change time.
     */
    private void transitionTo(MonitorState newState, Instant now) {
        this.state = newState;
        this.stateChangeTime = now;
    }

    private double getCpuLoad() {
        double rawCpuLoad = getRawCpuLoad();

        // Short-circuit if CPU monitoring unavailable (always returns 0)
        if (cpuMonitoringUnavailable) {
            return 0.0; // No smoothing needed for constant 0
        }

        // Apply exponential moving average for smoothing
        if (isFirstSample) {
            smoothedCpuLoad = rawCpuLoad;
            isFirstSample = false;
        } else {
            // EMA formula: smoothed = (new_value * alpha) + (previous_smoothed * (1 - alpha))
            smoothedCpuLoad = (rawCpuLoad * SMOOTHING_FACTOR) + (smoothedCpuLoad * (1 - SMOOTHING_FACTOR));
        }

        LOGGER.log(Level.FINE, "(getCpuLoad) Raw CPU: {0}%, Smoothed CPU: {1}%", new Object[]{String.format("%.2f", rawCpuLoad), String.format("%.2f", smoothedCpuLoad)});

        return smoothedCpuLoad;
    }

    private double getRawCpuLoad() {
        try {
            Class<?> sunOsClass = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (sunOsClass.isInstance(osMxBean)) {
                Method method = sunOsClass.getMethod("getProcessCpuLoad");
                Object result = method.invoke(osMxBean);
                if (result instanceof Double) {
                    double cpuLoad = (Double) result;
                    if (cpuLoad >= 0) {
                        LOGGER.log(Level.FINER, "(getRawCpuLoad) Using getProcessCpuLoad(): {0}%", String.format("%.2f", cpuLoad * 100));
                        return cpuLoad * 100.0;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "getProcessCpuLoad() not available: " + e.getMessage());
        }

        // Fallback: Return 0 to disable CPU-based pause/resume gracefully
        // Log warning once to avoid spamming logs
        if (!cpuMonitoringUnavailable) {
            LOGGER.log(Level.SEVERE, "CPU load monitoring not available on this platform. CPU-based pause/resume will not function. " +
                       "This may occur on non-Sun/Oracle JVMs or restricted environments.");
            cpuMonitoringUnavailable = true;
        }

        return 0.0; // Return 0 to prevent monitor from triggering (will stay NORMAL)
    }


    @Override
    public MonitorMetrics getMetrics() {
        return new MonitorMetrics(
                ID,
                getCpuLoad(),
                hotThreshold,
                coldThreshold,
                state,
                Duration.between(stateChangeTime, Instant.now())
        );
    }
}

