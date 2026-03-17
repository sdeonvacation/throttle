package io.github.throttle.service.factory;

import io.github.throttle.service.api.ThrottleService;
import io.github.throttle.service.config.ThrottleConfig;
import io.github.throttle.service.config.OverflowHandler;
import io.github.throttle.service.config.OverflowPolicy;
import io.github.throttle.service.core.ThrottleServiceImpl;
import io.github.throttle.service.monitor.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Factory for creating ThrottleService instances.
 * Provides fluent builder API for configuration.
 */
public class ThrottleServiceFactory {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ThrottleConfig.Builder configBuilder = ThrottleConfig.builder();
        private final List<ResourceMonitor> monitors = new ArrayList<ResourceMonitor>();
        private boolean cpuMonitorEnabled = true;
        private boolean memoryMonitorEnabled = true;
        private double cpuHot = 80.0;
        private double cpuCold = 50.0;
        private double memoryHot = 70.0;
        private double memoryCold = 50.0;
        private Duration hysteresis = Duration.ofSeconds(10);


        public Builder queueCapacity(int capacity) {
            configBuilder.queueCapacity(capacity);
            return this;
        }

        public Builder overflowPolicy(OverflowPolicy policy) {
            configBuilder.overflowPolicy(policy);
            return this;
        }

        public Builder overflowHandler(OverflowHandler handler) {
            configBuilder.overflowHandler(handler);
            return this;
        }

        /**
         * Set the global starvation threshold.
         * Tasks waiting longer than this duration will be boosted to the next higher priority.
         *
         * @param threshold Duration after which a task is considered starving (default: 2 hours)
         */
        public Builder starvationThreshold(Duration threshold) {
            configBuilder.starvationThreshold(threshold);
            return this;
        }

        /**
         * Set the cold monitoring interval.
         * When tasks are paused due to resource pressure, monitors are sampled at this interval
         * to detect when resources cool down and tasks can be resumed.
         *
         * @param interval Duration between monitor samples while paused (default: 5s)
         */
        public Builder coldMonitoringInterval(Duration interval) {
            configBuilder.coldMonitoringInterval(interval);
            return this;
        }

        /**
         * Set the hot monitoring interval (debounce interval).
         * When workers hit checkpoints during normal operation, monitor sampling is debounced
         * using this interval to prevent redundant sampling by multiple workers.
         *
         * @param interval Duration for debouncing monitor samples at checkpoints (default: 100ms)
         */
        public Builder hotMonitoringDebounceInterval(Duration interval) {
            configBuilder.hotMonitoringDebounceInterval(interval);
            return this;
        }

        public Builder maxPauseCount(int count) {
            configBuilder.maxPauseCount(count);
            return this;
        }

        public Builder taskTerminationEnabled(boolean enabled) {
            configBuilder.taskTerminationEnabled(enabled);
            return this;
        }

        /**
         * Set the worker thread pool.
         * If not provided, a default pool will be created.
         *
         * @param pool ExecutorService for worker threads that execute task chunks
         */
        public Builder workerThreadPool(ExecutorService pool) {
            configBuilder.workerThreadPool(pool);
            return this;
        }

        /**
         * Set the monitoring thread pool.
         * If not provided, a default single-threaded pool will be created.
         *
         * @param pool ExecutorService for monitoring and coordination (pause/resume decisions)
         */
        public Builder monitoringThreadPool(ExecutorService pool) {
            configBuilder.monitoringThreadPool(pool);
            return this;
        }

        public Builder cpuMonitor(double hot, double cold) {
            this.cpuHot = hot;
            this.cpuCold = cold;
            this.cpuMonitorEnabled = true;
            return this;
        }

        public Builder memoryMonitor(double hot, double cold) {
            this.memoryHot = hot;
            this.memoryCold = cold;
            this.memoryMonitorEnabled = true;
            return this;
        }

        public Builder disableCpuMonitor() {
            this.cpuMonitorEnabled = false;
            return this;
        }

        public Builder disableMemoryMonitor() {
            this.memoryMonitorEnabled = false;
            return this;
        }

        public Builder addMonitor(ResourceMonitor monitor) {
            this.monitors.add(monitor);
            return this;
        }

        public Builder hysteresis(Duration duration) {
            this.hysteresis = duration;
            return this;
        }

        public ThrottleService build() {
            List<ResourceMonitor> allMonitors = new ArrayList<ResourceMonitor>(monitors);

            if (cpuMonitorEnabled) {
                allMonitors.add(new CpuMonitor(cpuHot, cpuCold, hysteresis));
            }

            if (memoryMonitorEnabled) {
                allMonitors.add(new MemoryMonitor(memoryHot, memoryCold, hysteresis));
            }

            ThrottleConfig config = configBuilder.build();
            return new ThrottleServiceImpl(config, allMonitors);
        }
    }
}
