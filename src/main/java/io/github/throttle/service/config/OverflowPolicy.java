package io.github.throttle.service.config;

/**
 * Queue overflow policy.
 * Determines behavior when queue is full.
 */
public enum OverflowPolicy {
    REJECT,          // Reject new tasks (throw exception)
    BLOCK,           // Block until space available
    DISCARD_OLDEST,  // Remove oldest task from queue
    CUSTOM           // Use custom handler
}

