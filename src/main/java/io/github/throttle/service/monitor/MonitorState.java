package io.github.throttle.service.monitor;

/**
 * Resource monitor states with hysteresis.
 */
public enum MonitorState {
    NORMAL,     // Resource usage is normal
    BREACHING,  // Resource exceeded hot threshold (hysteresis period)
    HOT,        // Resource is hot (pause execution)
    COOLING     // Resource dropped below cold threshold (hysteresis period)
}

