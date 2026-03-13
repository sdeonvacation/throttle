package io.github.throttle.service.monitor;

import io.github.throttle.service.base.MonitorMetrics;

/**
 * Interface for resource monitors (CPU, Memory, Custom).
 */
public interface ResourceMonitor {
    /**
     * Unique identifier for this monitor.
     */
    String getId();

    /**
     * Evaluate current resource state.
     * Called periodically by executor service.
     *
     * @return Current state (NORMAL, BREACHING, HOT, COOLING)
     */
    MonitorState evaluate();

    /**
     * Get current metrics for observability.
     */
    MonitorMetrics getMetrics();
}

