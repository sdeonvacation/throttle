package io.github.throttle.service.base;

/**
 * Task priority levels.
 * Higher priority tasks are scheduled before lower priority tasks.
 */
public enum Priority {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    private final int value;

    Priority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * Boost priority by one level (anti-starvation).
     * HIGH stays HIGH, MEDIUM→HIGH, LOW→MEDIUM.
     */
    public Priority boost() {
        switch (this) {
            case LOW: return MEDIUM;
            case MEDIUM: return HIGH;
            case HIGH: return HIGH;
            default: return this;
        }
    }
}

