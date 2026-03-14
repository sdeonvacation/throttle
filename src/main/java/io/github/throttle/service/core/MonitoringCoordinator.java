package io.github.throttle.service.core;

import io.github.throttle.service.base.MonitorMetrics;
import io.github.throttle.service.monitor.MonitorState;
import io.github.throttle.service.monitor.ResourceMonitor;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates resource monitoring.
 * Samples monitors and provides current state information.
 */
public class MonitoringCoordinator {
    private static final Logger LOGGER = Logger.getLogger(MonitoringCoordinator.class.getName());

    private final List<ResourceMonitor> monitors;
    private volatile boolean anyHot = false;
    private volatile boolean allNormal = false;

    public MonitoringCoordinator(List<ResourceMonitor> monitors) {
        this.monitors = new ArrayList<ResourceMonitor>(monitors);
        LOGGER.info("MonitoringCoordinator initialized with " + monitors.size() + " monitors");
    }

    /**
     * Sample all monitors and update state.
     * Called after each chunk completion.
     */
    public void sampleMonitors() {
        boolean hot = false;
        boolean normal = true;

        LOGGER.log(Level.FINE, "(sampleMonitors) Monitoring Check Started with " + monitors + " monitors");

        for (ResourceMonitor monitor : monitors) {
            MonitorState state;
            try {
                state = monitor.evaluate();
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "(sampleMonitors) Monitor [" + monitor.getId() + "] threw during evaluate() — treating as NORMAL (fail-open): " + ex.getMessage());
                continue; // skip this monitor; do not flip the hot/normal flags
            }

            MonitorMetrics metrics = monitor.getMetrics();

            // Log monitoring metrics for observability
            LOGGER.log(Level.FINE, "(sampleMonitors) Monitor [" + monitor.getId() + "] - State: " + state + ", Metrics: " + formatMetrics(metrics));

            if (state == MonitorState.HOT) {
                hot = true;
                normal = false;
                LOGGER.warning("(sampleMonitors) Monitor " + monitor.getId() + " is HOT - Resource pressure detected!");
            } else if (state != MonitorState.NORMAL) {
                normal = false;
                LOGGER.log(Level.FINE, "(sampleMonitors) Monitor " + monitor.getId() + " is in non-NORMAL state: " + state);
            }
        }

        // Log state transitions
        boolean previousHot = this.anyHot;
        boolean previousNormal = this.allNormal;

        this.anyHot = hot;
        this.allNormal = normal;

        if (hot && !previousHot) {
            LOGGER.info("(sampleMonitors) System State Transition: NORMAL -> HOT");
        } else if (normal && !previousNormal) {
            LOGGER.info("(sampleMonitors) System State Transition: HOT -> NORMAL");
        }

        LOGGER.log(Level.FINE, "(sampleMonitors) Monitoring Check Ended. AnyHot: " + hot + ", AllNormal: " + normal);
    }

    /**
     * Check if any monitor is in HOT state.
     */
    public boolean isAnyHot() {
        return anyHot;
    }

    /**
     * Check if all monitors are in NORMAL state.
     */
    public boolean isAllNormal() {
        return allNormal;
    }

    /**
     * Get all monitors for observability.
     */
    public List<ResourceMonitor> getMonitors() {
        return new ArrayList<>(monitors);
    }

    /**
     * Format metrics for logging.
     */
    private String formatMetrics(MonitorMetrics metrics) {
        if (metrics == null) {
            return "N/A";
        }
        return String.format("current=%.2f%%, hotThreshold=%.2f%%, state=%s",
            metrics.getCurrentValue(), metrics.getHotThreshold(), metrics.getState());
    }
}


