package io.github.throttle.service.base;

import io.github.throttle.service.monitor.MonitorState;

import java.time.Duration;

/**
 * Metrics for a resource monitor.
 */
public class MonitorMetrics {
    private final String monitorId;
    private final double currentValue;
    private final double hotThreshold;
    private final double coldThreshold;
    private final MonitorState state;
    private final Duration timeInState;

    public MonitorMetrics(String monitorId, double currentValue, double hotThreshold,
                         double coldThreshold, MonitorState state, Duration timeInState) {
        this.monitorId = monitorId;
        this.currentValue = currentValue;
        this.hotThreshold = hotThreshold;
        this.coldThreshold = coldThreshold;
        this.state = state;
        this.timeInState = timeInState;
    }

    public String getMonitorId() {
        return monitorId;
    }

    public double getCurrentValue() {
        return currentValue;
    }

    public double getHotThreshold() {
        return hotThreshold;
    }

    public double getColdThreshold() {
        return coldThreshold;
    }

    public MonitorState getState() {
        return state;
    }

    public Duration getTimeInState() {
        return timeInState;
    }

    @Override
    public String toString() {
        return "MonitorMetrics{" +
                "monitorId='" + monitorId + '\'' +
                ", currentValue=" + currentValue +
                ", hotThreshold=" + hotThreshold +
                ", coldThreshold=" + coldThreshold +
                ", state=" + state +
                ", timeInState=" + timeInState +
                '}';
    }
}

