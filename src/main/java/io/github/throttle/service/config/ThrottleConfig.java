package io.github.throttle.service.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

/**
 * Configuration for ThrottleService.
 */
public class ThrottleConfig {
    private final int queueCapacity;
    private final OverflowPolicy overflowPolicy;
    private final Duration starvationThreshold;
    private final Duration coldMonitoringInterval;
    private final Duration hotMonitoringDebounceInterval;
    private final int maxPauseCount;
    private final boolean taskTerminationEnabled;
    private final OverflowHandler overflowHandler;
    private final ExecutorService workerExecutorService;
    private final ExecutorService controlPlaneExecutorService;

    private ThrottleConfig(Builder builder) {
        this.queueCapacity = builder.queueCapacity;
        this.overflowPolicy = builder.overflowPolicy;
        this.starvationThreshold = builder.starvationThreshold;
        this.coldMonitoringInterval = builder.coldMonitoringInterval;
        this.hotMonitoringDebounceInterval = builder.hotMonitoringDebounceInterval;
        this.maxPauseCount = builder.maxPauseCount;
        this.taskTerminationEnabled = builder.taskTerminationEnabled;
        this.overflowHandler = builder.overflowHandler;
        this.workerExecutorService = builder.workerExecutorService;
        this.controlPlaneExecutorService = builder.controlPlaneExecutorService;
    }


    public int getQueueCapacity() {
        return queueCapacity;
    }

    public OverflowPolicy getOverflowPolicy() {
        return overflowPolicy;
    }

    /**
     * Get the global starvation threshold.
     * Tasks waiting longer than this duration will be boosted to the next higher priority.
     */
    public Duration getStarvationThreshold() {
        return starvationThreshold;
    }

    /**
     * Get the cold monitoring interval.
     * When tasks are paused, monitors are sampled at this interval to detect cooldown.
     */
    public Duration getColdMonitoringInterval() {
        return coldMonitoringInterval;
    }

    /**
     * Get the hot monitoring interval (debounce interval).
     * When workers hit checkpoints, monitor sampling is debounced using this interval
     * to prevent redundant sampling by multiple workers.
     * Workers skip sampling if another worker sampled within this interval.
     */
    public Duration getHotMonitoringDebounceInterval() {
        return hotMonitoringDebounceInterval;
    }

    public int getMaxPauseCount() {
        return maxPauseCount;
    }

    public boolean isTaskTerminationEnabled() {
        return taskTerminationEnabled;
    }

    public OverflowHandler getOverflowHandler() {
        return overflowHandler;
    }

    public ExecutorService getWorkerExecutorService() {
        return workerExecutorService;
    }

    public ExecutorService getControlPlaneExecutorService() {
        return controlPlaneExecutorService;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int queueCapacity = 25;
        private OverflowPolicy overflowPolicy = OverflowPolicy.BLOCK;
        private Duration starvationThreshold = Duration.ofHours(2);
        private Duration coldMonitoringInterval = Duration.ofSeconds(5);
        private Duration hotMonitoringDebounceInterval = Duration.ofSeconds(5);
        private int maxPauseCount = 5;
        private boolean taskTerminationEnabled = true;
        private OverflowHandler overflowHandler = null;
        private ExecutorService workerExecutorService = null;
        private ExecutorService controlPlaneExecutorService = null;


        public Builder queueCapacity(int capacity) {
            this.queueCapacity = capacity;
            return this;
        }

        public Builder overflowPolicy(OverflowPolicy policy) {
            this.overflowPolicy = policy;
            return this;
        }

        /**
         * Set the global starvation threshold.
         * Tasks waiting longer than this duration will be boosted to the next higher priority.
         * Default: 2 hours
         *
         * @param threshold Duration after which a task is considered starving
         */
        public Builder starvationThreshold(Duration threshold) {
            this.starvationThreshold = threshold;
            return this;
        }

        /**
         * Set the cold monitoring interval.
         * When tasks are paused due to resource pressure, monitors are sampled at this interval
         * to detect when resources cool down and tasks can be resumed.
         * Default: 5 seconds
         *
         * @param interval Duration between monitor samples while paused
         */
        public Builder coldMonitoringInterval(Duration interval) {
            this.coldMonitoringInterval = interval;
            return this;
        }

        /**
         * Set the hot monitoring interval (debounce interval).
         * When workers hit checkpoints during normal operation, monitor sampling is debounced
         * using this interval to prevent redundant sampling by multiple workers.
         * Workers skip sampling if another worker sampled within this interval.
         * Default: 5000 milliseconds
         *
         * @param interval Duration for debouncing monitor samples at checkpoints
         */
        public Builder hotMonitoringDebounceInterval(Duration interval) {
            this.hotMonitoringDebounceInterval = interval;
            return this;
        }

        public Builder maxPauseCount(int count) {
            this.maxPauseCount = count;
            return this;
        }

        public Builder taskTerminationEnabled(boolean enabled) {
            this.taskTerminationEnabled = enabled;
            return this;
        }

        public Builder overflowHandler(OverflowHandler handler) {
            this.overflowHandler = handler;
            return this;
        }

        /**
         * Set the worker thread pool.
         * If not provided, a default pool will be created.
         *
         * @param pool ExecutorService for worker threads
         */
        public Builder workerExecutorService(ExecutorService pool) {
            this.workerExecutorService = pool;
            return this;
        }

        /**
         * Set the control plane thread pool.
         * If not provided, a default single-threaded pool will be created.
         *
         * @param pool ExecutorService for control plane (monitoring, decision making)
         */
        public Builder controlPlaneExecutorService(ExecutorService pool) {
            this.controlPlaneExecutorService = pool;
            return this;
        }

        public ThrottleConfig build() {
            return new ThrottleConfig(this);
        }
    }
}
