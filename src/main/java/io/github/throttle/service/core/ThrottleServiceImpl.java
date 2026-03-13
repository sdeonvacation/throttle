package io.github.throttle.service.core;

import io.github.throttle.service.api.ThrottleService;
import io.github.throttle.service.api.ChunkableTask;
import io.github.throttle.service.base.ChunkableTaskComparator;
import io.github.throttle.service.base.ExecutorMetrics;
import io.github.throttle.service.config.ThrottleConfig;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.throttle.service.monitor.ResourceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of ThrottleService.
 * Provides priority-based task scheduling with resource-aware pause/resume.
 *
 * This implementation delegates to specialized components:
 * - TaskExecutor: Executes tasks chunk-by-chunk
 * - MonitoringCoordinator: Samples resource monitors
 * - ExecutionCoordinator: Makes pause/resume and anti-starvation decisions
 */
public class ThrottleServiceImpl implements ThrottleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ThrottleServiceImpl.class);

    private final PriorityBlockingQueue<ChunkableTask<?>> priorityQueue;
    private final int queueCapacity;
    private final ThrottleConfig config;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    // Thread-safe capacity enforcement using semaphore
    // Prevents TOCTOU race conditions when checking queue capacity
    private final Semaphore queuePermits;

    // Modular components
    private final MonitoringCoordinator monitoringCoordinator;
    private final ExecutionCoordinator executionCoordinator;
    private final TaskExecutor taskExecutor;

    public ThrottleServiceImpl(ThrottleConfig config,
                                       List<ResourceMonitor> monitors) {
        this.config = config;

        // Initialize priority queue with capacity enforcement
        this.queueCapacity = config.getQueueCapacity();
        this.priorityQueue = new PriorityBlockingQueue<>(
                queueCapacity,
                new ChunkableTaskComparator()
        );

        // Initialize semaphore for thread-safe capacity enforcement
        this.queuePermits = new Semaphore(queueCapacity, true); // fair semaphore

        // Create modular components
        this.monitoringCoordinator = new MonitoringCoordinator(monitors);
        this.executionCoordinator = new ExecutionCoordinator(config, priorityQueue, monitoringCoordinator,
                                               config.getControlPlaneExecutorService());
        this.taskExecutor = new TaskExecutor(priorityQueue, config, config.getWorkerExecutorService(),
                executionCoordinator, monitoringCoordinator, queuePermits);

        // Wire the circular reference: executionCoordinator needs taskExecutor to
        // interrupt workers blocked in take() when a pause is triggered.
        executionCoordinator.setTaskExecutor(this.taskExecutor);

        // Start components
        executionCoordinator.start();
        taskExecutor.start();

        LOGGER.info("ThrottleService started with queue capacity: {}", queueCapacity);
    }

    @Override
    public Future<Void> submit(ChunkableTask<?> task) {
        if (isShutdown()) {
            throw new RejectedExecutionException("Throttle Service is shut down");
        }

        // Set enqueue time and initialize current priority
        task.setEnqueueTime(Instant.now());
        if (task.getCurrentPriority() == null) {
            task.setCurrentPriority(task.getPriority());
        }

        // Thread-safe capacity enforcement using semaphore
        // This prevents TOCTOU race conditions between checking size and offering to queue
        boolean permitAcquired = queuePermits.tryAcquire();
        if (!permitAcquired) {
            // Queue is full - handle overflow policy
            permitAcquired = handleQueueOverflow(task);
            if (!permitAcquired) {
                // Overflow handling couldn't acquire a permit (REJECT or CUSTOM policies)
                task.cancel(false);
                throw new RejectedExecutionException("Queue is full after overflow handling (capacity: " + queueCapacity + ")");
            }
        }

        // Permit acquired - task can be enqueued
        try {
            priorityQueue.offer(task);
            LOGGER.info("(submit) Submitted task: {} with priority: {}", task.getTaskId(), task.getPriority());
            return task;
        } catch (Exception e) {
            // If offer() fails for any reason, release the permit and propagate the exception
            queuePermits.release();
            throw e;
        }
    }

    @Override
    public void pauseAll() {
        executionCoordinator.pauseAll();
    }

    @Override
    public void resumeAll() {
        executionCoordinator.resumeAll();
    }

    @Override
    public boolean isPaused() {
        return executionCoordinator.getIsPaused();
    }

    @Override
    public ExecutorMetrics getMetrics() {
        return new ExecutorMetrics(
                taskExecutor.getActiveThreadCount(),
                priorityQueue.size(),
                taskExecutor.getTasksCompleted(),
                taskExecutor.getTasksFailed(),
                taskExecutor.getTasksKilled(),
                executionCoordinator.getPauseCount(),
                taskExecutor.getTotalPauseDuration(),
                executionCoordinator.getIsPaused()
        );
    }

    @Override
    public List<ChunkableTask<?>> getKilledTasks() {
        return executionCoordinator.getKilledTasks();
    }

    @Override
    public List<ResourceMonitor> getMonitors() {
        return monitoringCoordinator.getMonitors();
    }

    /**
     * Handle queue overflow based on configured policy.
     *
     * @param task The task being submitted
     * @return true if a permit was acquired for the task, false otherwise
     * @throws RejectedExecutionException if the policy is REJECT or CUSTOM
     */
    private boolean handleQueueOverflow(ChunkableTask<?> task) {
        switch (config.getOverflowPolicy()) {
            case REJECT:
                // Cancel the task and throw - caller must handle the rejected task
                task.cancel(false);
                throw new RejectedExecutionException("Queue is full (capacity: " + queueCapacity + ")");

            case BLOCK:
                // Wait until a permit becomes available (released when a task is dequeued)
                // This blocks the calling thread until space is available
                try {
                    LOGGER.debug("(handleQueueOverflow) Queue full, blocking until space available");
                    queuePermits.acquire();
                    LOGGER.debug("(handleQueueOverflow) Queue space available, proceeding with submit");
                    return true; // Permit acquired
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    task.cancel(true);
                    throw new RejectedExecutionException("Interrupted while waiting for queue space");
                }

            case DISCARD_OLDEST:
                // Remove oldest task, cancel it, and release its permit for reuse
                ChunkableTask<?> oldest = priorityQueue.poll();
                if (oldest != null) {
                    LOGGER.warn("(handleQueueOverflow) Discarded oldest task: {}", oldest.getTaskId());
                    oldest.cancel(false);
                    // The oldest task's permit needs to be released since it won't be
                    // dequeued by a worker. This permit will be reused by the new task.
                    queuePermits.release();
                    // Now try to acquire a permit for the new task
                    return queuePermits.tryAcquire();
                } else {
                    // Queue is empty but no permit available - should not happen
                    LOGGER.error("(handleQueueOverflow) DISCARD_OLDEST: Queue is empty but no permit available");
                    return false;
                }

            case CUSTOM:
                // Delegate to custom handler
                if (config.getOverflowHandler() != null) {
                    config.getOverflowHandler().handle(task);
                }
                task.cancel(false);
                throw new RejectedExecutionException("Task rejected by custom overflow handler");

            default:
                return false;
        }
    }

    @Override
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            LOGGER.info("Shutting down ThrottleService");
            executionCoordinator.shutdown();
            taskExecutor.shutdown();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        executionCoordinator.shutdownNow();
        taskExecutor.shutdownNow();
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean isTerminated() {
        return taskExecutor.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return taskExecutor.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        throw new UnsupportedOperationException("Use submit(ChunkableTask) instead");
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        throw new UnsupportedOperationException("Use submit(ChunkableTask) instead");
    }

    @Override
    public Future<?> submit(Runnable task) {
        throw new UnsupportedOperationException("Use submit(ChunkableTask) instead");
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        throw new UnsupportedOperationException("Use submit(ChunkableTask) instead");
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        throw new UnsupportedOperationException("Use submit(ChunkableTask) instead");
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        throw new UnsupportedOperationException("Use submit(ChunkableTask) instead");
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throw new UnsupportedOperationException("Use submit(ChunkableTask) instead");
    }

    @Override
    public void execute(Runnable command) {
        throw new UnsupportedOperationException("Use submit(ChunkableTask) instead");
    }
}
