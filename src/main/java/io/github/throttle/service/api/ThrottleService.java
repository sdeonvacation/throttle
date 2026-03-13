package io.github.throttle.service.api;

import io.github.throttle.service.base.ExecutorMetrics;
import io.github.throttle.service.monitor.ResourceMonitor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Executor service with priority-based scheduling and resource-aware pause/resume.
 */
public interface ThrottleService extends ExecutorService {

    /**
     * Submit a chunkable task for execution.
     * Task will be queued by priority and executed when thread available.
     *
     * Note: The task processes items of type T, but returns no typed result.
     * The Future<Void> completes when all chunks are processed successfully,
     * or completes exceptionally if the task fails or is killed.
     *
     * @param task The chunkable task to execute (processes items of type T)
     * @return Future<Void> representing pending completion (no result value)
     */
    Future<Void> submit(ChunkableTask<?> task);

    /**
     * Pause all task execution.
     * Currently executing chunks will complete, then threads will wait.
     */
    void pauseAll();

    /**
     * Resume all task execution.
     * Threads will continue processing from next chunk.
     */
    void resumeAll();

    /**
     * Check if executor is currently paused.
     */
    boolean isPaused();

    /**
     * Get current metrics.
     */
    ExecutorMetrics getMetrics();

    /**
     * Get list of killed tasks (paused too many times).
     */
    List<ChunkableTask<?>> getKilledTasks();

    /**
     * Get resource monitors for observability.
     * Returns CPU, Memory, and any custom monitors.
     *
     * @return List of resource monitors with current metrics
     */
    List<ResourceMonitor> getMonitors();
}

