package io.github.throttle.service.core;

import io.github.throttle.service.api.ChunkableTask;
import io.github.throttle.service.base.TaskTerminatedException;
import io.github.throttle.service.config.ThrottleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Task handler responsible for processing tasks from the priority queue.
 * Handles chunk-by-chunk execution with pause points.
 */
public class TaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutor.class);
    private static final int DEFAULT_POOL_SIZE = 2;


    private final PriorityBlockingQueue<ChunkableTask<?>> priorityQueue;
    private final ThrottleConfig config;
    private final ExecutorService workerExecutorService;
    private final boolean ownsWorkerExecutorService;
    private final int poolSize;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final ExecutionCoordinator executionCoordinator;
    private final MonitoringCoordinator monitoringCoordinator;

    // Semaphore for thread-safe queue capacity enforcement
    // Released when task is dequeued to allow new submissions
    private final Semaphore queuePermits;

    // Latch counting down as each worker thread exits - used by awaitTermination
    // for both owned and client-provided pools
    private volatile CountDownLatch workerLatch;

    // Idle threads are those blocked in take() waiting for work.
    // Only idle threads are interrupted when a pause is triggered — busy threads
    // (mid-chunk) must not be interrupted; they observe the pause flag at their
    // next chunk checkpoint.
    private final CopyOnWriteArrayList<Thread> idleThreads = new CopyOnWriteArrayList<>();

    // Metrics
    private final AtomicLong tasksCompleted = new AtomicLong(0);
    private final AtomicLong tasksFailed = new AtomicLong(0);
    private final AtomicLong tasksKilled = new AtomicLong(0);
    private final AtomicLong totalPauseDuration = new AtomicLong(0);


    // Lock for coordinating monitor sampling at checkpoints (prevents redundant sampling)
    private final Object checkpointMonitoringLock = new Object();
    private volatile long lastMonitorSampleTime = 0;
    private final long hotMonitoringIntervalMs; // Configurable debounce interval

    public TaskExecutor(PriorityBlockingQueue<ChunkableTask<?>> priorityQueue,
                        ThrottleConfig config,
                        ExecutorService workerExecutorService,
                        ExecutionCoordinator executionCoordinator,
                        MonitoringCoordinator monitoringCoordinator,
                        Semaphore queuePermits) {
        this.priorityQueue = priorityQueue;
        this.config = config;
        this.executionCoordinator = executionCoordinator;
        this.monitoringCoordinator = monitoringCoordinator;
        this.queuePermits = queuePermits;
        this.hotMonitoringIntervalMs = config.getHotMonitoringDebounceInterval().toMillis();

        // Use provided pool or create default
        if (workerExecutorService != null) {
            this.workerExecutorService = workerExecutorService;
            this.ownsWorkerExecutorService = false;
            this.poolSize = getPoolSizeFromExecutorService(workerExecutorService);
            LOGGER.info("TaskHandler using client-provided worker pool");
        } else {
            this.poolSize = DEFAULT_POOL_SIZE;
            this.workerExecutorService = createDefaultWorkerExecutorService();
            this.ownsWorkerExecutorService = true;
            LOGGER.info("TaskHandler created default worker pool with size: {}", DEFAULT_POOL_SIZE);
        }
    }

    /**
     * Start worker threads.
     */
    public void start() {
        workerLatch = new CountDownLatch(poolSize);
        for (int i = 0; i < poolSize; i++) {
            workerExecutorService.execute(new WorkerThread());
        }
        LOGGER.info("Started {} worker threads for adaptive executor", poolSize);
    }

    /**
     * Interrupt only the threads currently blocked in take() (idle threads).
     * Called by ExecutionCoordinator.executePause() so idle workers observe
     * the pause flag promptly instead of waiting for the next task to arrive.
     * Busy threads (mid-chunk) are NOT interrupted — they observe the pause
     * flag at their next chunk checkpoint.
     */
    public void interruptIdleWorkers() {
        for (Thread t : idleThreads) {
            t.interrupt();
        }
    }

    /**
     * Shutdown worker pool gracefully.
     * Sets the shutdown flag and interrupts workers so threads blocked in take()
     * wake up, observe the flag, and exit the loop cleanly.
     * Does NOT interrupt tasks that are currently executing a chunk — those
     * continue until their current chunk finishes, then exit at the top of the loop.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            LOGGER.info("Shutting down Adaptive Executor worker - initiating graceful shutdown of worker threads");
            interruptIdleWorkers();
            if (ownsWorkerExecutorService) {
                workerExecutorService.shutdown();
            }
        }
    }

    /**
     * Shutdown worker pool immediately.
     * Sets the shutdown flag and interrupts worker threads via the underlying pool.
     * Threads blocked on take() or awaitResume() will receive InterruptedException and exit.
     * Tasks currently executing a chunk will be interrupted mid-chunk.
     */
    public void shutdownNow() {
        shutdown.set(true);
        if (ownsWorkerExecutorService) {
            // shutdownNow() sends interrupt to all threads, unblocking take() and awaitResume()
            workerExecutorService.shutdownNow();
        }
        // For client-provided pools: the shutdown flag is set. The client must
        // interrupt their pool externally (e.g. workerPool.shutdownNow()) for immediate stop.
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    public boolean isTerminated() {
        return shutdown.get() && workerLatch != null && workerLatch.getCount() == 0;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (workerLatch == null) return true;
        return workerLatch.await(timeout, unit);
    }

    public int getActiveThreadCount() {
        if (workerExecutorService instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) workerExecutorService).getActiveCount();
        }
        return 0;
    }

    public long getTasksCompleted() {
        return tasksCompleted.get();
    }

    public long getTasksFailed() {
        return tasksFailed.get();
    }

    public long getTasksKilled() {
        return tasksKilled.get();
    }

    public long getTotalPauseDuration() {
        return totalPauseDuration.get();
    }

    /**
     * Try to determine pool size from ExecutorService.
     * Defaults to DEFAULT_POOL_SIZE if cannot determine.
     */
    private int getPoolSizeFromExecutorService(ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) executor).getCorePoolSize();
        }
        LOGGER.debug("(getPoolSizeFromExecutorService) Cannot determine pool size from ExecutorService, using default: {}", DEFAULT_POOL_SIZE);
        return DEFAULT_POOL_SIZE;
    }

    private ExecutorService createDefaultWorkerExecutorService() {
        return new ThreadPoolExecutor(
                DEFAULT_POOL_SIZE,
                DEFAULT_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "Throttle-TaskExecutor-" + counter.incrementAndGet());
                        t.setDaemon(false);
                        return t;
                    }
                }
        );
    }

    /**
     * Package-private worker loop method that the WorkerThread will call.
     */
    void runExecutionLoop() {
        LOGGER.info("(runWorkerLoop) TaskExecutor thread {} started", Thread.currentThread().getName());

        while (!shutdown.get()) {
            ChunkableTask<?> task = null;
            boolean permitReleased = false;
            try {
                // Register as idle before blocking in take() so interruptIdleWorkers()
                // can wake us up promptly when a pause is triggered.
                idleThreads.add(Thread.currentThread());
                try {
                    task = priorityQueue.take();
                    // Task dequeued successfully - release permit to allow new submissions
                    queuePermits.release();
                    permitReleased = true;
                } finally {
                    // Deregister immediately — whether take() returned normally or threw.
                    // From this point on this thread is no longer idle.
                    idleThreads.remove(Thread.currentThread());
                }

                // If a pause was triggered while we were blocked in take(), executePause()
                // interrupted this thread so take() threw InterruptedException (caught below).

                executeTask(task);

            } catch (InterruptedException e) {
                // Two reasons we can be interrupted:
                //   1. executePause() interrupted us while we were in take() — system is paused.
                //   2. shutdownNow() interrupted us — we should exit.
                // When take() throws InterruptedException, task is always null
                // (take() never returned a value), so there is nothing to re-enqueue.
                if (executionCoordinator.getIsPaused() && !shutdown.get()) {
                    // Case 1: pause signal — wait for resume and continue.
                    try {
                        waitForResume();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // Case 2: genuine shutdown interrupt — exit.
                    Thread.currentThread().interrupt();
                    LOGGER.info("(runWorkerLoop) TaskExecutor thread {} interrupted", Thread.currentThread().getName());
                    break;
                }
            } catch (Exception e) {
                LOGGER.error("(runWorkerLoop) TaskExecutor thread error: {}", e.getMessage(), e);
                if (task != null) {
                    // Determine root cause to detect task termination vs other failures
                    Throwable rootCause = e;
                    if (e instanceof RuntimeException && e.getCause() != null) {
                        rootCause = e.getCause();
                    }

                    if (rootCause instanceof TaskTerminatedException) {
                        LOGGER.info("(runWorkerLoop) Task {} was terminated; termination was already recorded", task.getTaskId());
                        // No further action - termination handling already performed in executeTask
                    } else {
                        try {
                            task.onError(e);
                        } catch (Exception ex) {
                            LOGGER.error("(runWorkerLoop) Task {}: onError() itself threw: {}",
                                task.getTaskId(), ex.getMessage());
                        }
                        try {
                            task.failTask(e);
                        } catch (Exception ex) {
                            LOGGER.error("(runWorkerLoop) Task {}: failTask() threw: {}",
                                task.getTaskId(), ex.getMessage());
                        }
                        tasksFailed.incrementAndGet();
                    }
                }
            }
        }

        LOGGER.info("(runWorkerLoop) TaskExecutor thread {} stopped", Thread.currentThread().getName());
    }

    /**
     * Wait for system to resume from paused state.
     * Idle workers call this to avoid dequeuing new tasks during resource pressure.
     * Uses wait/notify from control plane for efficiency.
     */
    private void waitForResume() throws InterruptedException {
        LOGGER.debug("(waitForResume) [{}] Waiting for system to resume",
            Thread.currentThread().getName());
        executionCoordinator.awaitResume();
        LOGGER.debug("(waitForResume) [{}] System resumed, will dequeue next task",
            Thread.currentThread().getName());
    }

    private void executeTask(ChunkableTask<?> task) {
        LOGGER.info("(executeTask) [{}] Starting task: {} (priority: {})",
            Thread.currentThread().getName(), task.getTaskId(), task.getPriority());

        try {
            int chunksProcessed = 0;

            // Execute task chunk-by-chunk with pause checks
            while (task.hasMoreChunks()) {
                if (shutdown.get()) {
                    task.onCancel();
                    task.cancel(true);
                    LOGGER.warn("(executeTask) [{}] Task {} cancelled due to shutdown",
                        Thread.currentThread().getName(), task.getTaskId());
                    return;
                }

                List<?> chunk = task.getNextChunk();
                if (chunk == null || chunk.isEmpty()) {
                    break;
                }

                // Process chunk
                long chunkStartTime = System.currentTimeMillis();
                processChunkSafely(task, chunk);
                long chunkDuration = System.currentTimeMillis() - chunkStartTime;
                chunksProcessed++;

                LOGGER.debug("(executeTask) [{}] Task {} processed chunk {} ({} items) in {}ms",
                    Thread.currentThread().getName(), task.getTaskId(),
                    chunksProcessed, chunk.size(), chunkDuration);

                // CHECKPOINT: Sample monitors and check if should pause (chunk-driven)
                pauseIfAnyMonitorHot();

                // Check if paused and wait for control plane to trigger resume
                handlePauseCheckpoint(task);

                // Check if should terminate (paused too many times)
                handleTerminationCondition(task);
            }

            // Task completed all chunks — invoke callback then resolve the future.
            // onComplete() is client code and may throw; we must always call completeTask()
            // so the Future is resolved regardless of callback behaviour.
            Throwable onCompleteError = null;
            try {
                task.onComplete();
            } catch (Exception ex) {
                onCompleteError = ex;
                LOGGER.error("(executeTask) [{}] Task {}: onComplete() threw: {}",
                    Thread.currentThread().getName(), task.getTaskId(), ex.getMessage());
            }

            if (onCompleteError != null) {
                // Callback failed — treat the task as failed so the future surfaces an exception
                try {
                    task.failTask(onCompleteError);
                } catch (Exception ex) {
                    LOGGER.error("(executeTask) Task {}: failTask() threw after onComplete failure: {}",
                        task.getTaskId(), ex.getMessage());
                }
                tasksFailed.incrementAndGet();
            } else {
                task.completeTask();
                tasksCompleted.incrementAndGet();
                LOGGER.info("(executeTask) [{}] Task {} completed successfully ({} chunks processed)",
                    Thread.currentThread().getName(), task.getTaskId(), chunksProcessed);
            }

            // Check for starving tasks after completion
            executionCoordinator.checkStarvation();

        } catch (Exception e) {
            // If this was a termination triggered internally (TaskTerminatedException wrapped in RuntimeException),
            // it has already been handled in handleTerminationCondition: task.onError(), task.failTask(), and tasksKilled incremented.
            // Do not increment tasksFailed again for killed tasks.
            Throwable rootCause = e;
            if (e instanceof RuntimeException && e.getCause() != null) {
                rootCause = e.getCause();
            }

            if (rootCause instanceof TaskTerminatedException) {
                LOGGER.info("(executeTask)[{}] Task {} termination handled: {}",
                    Thread.currentThread().getName(), task.getTaskId(), rootCause.getMessage());
                // No further action - killed count already updated
            } else {
                LOGGER.error("(executeTask)[{}] Task {} failed: {}",
                    Thread.currentThread().getName(), task.getTaskId(), e.getMessage(), e);
                try {
                    task.onError(e);
                } catch (Exception ex) {
                    LOGGER.error("(executeTask) Task {}: onError() itself threw: {}",
                        task.getTaskId(), ex.getMessage());
                }
                try {
                    task.failTask(e);
                } catch (Exception ex) {
                    LOGGER.error("(executeTask) Task {}: failTask() threw: {}",
                        task.getTaskId(), ex.getMessage());
                }
                tasksFailed.incrementAndGet();
            }
        } finally {
            LOGGER.debug("(executeTask) [{}] Task {} execution completed",
                Thread.currentThread().getName(), task.getTaskId());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void processChunkSafely(ChunkableTask<T> task, List<?> chunk) throws Exception {
        task.processChunk((List<T>) chunk);
    }

    /**
     * Monitor resources and make pause decision after chunk completion (chunk-driven).
     * This is called by worker threads at checkpoints, not continuously.
     *
     * Synchronized to prevent redundant monitor sampling when multiple workers
     * hit checkpoints simultaneously. Only one worker samples, others either wait
     * or skip if recently sampled.
     */
    private void pauseIfAnyMonitorHot() {
        synchronized (checkpointMonitoringLock) {
            // Skip sampling if another worker just sampled recently (within hotMonitoringInterval)
            long now = System.currentTimeMillis();
            long timeSinceLastSample = now - lastMonitorSampleTime;

            if (timeSinceLastSample < hotMonitoringIntervalMs) {
                LOGGER.debug("(pauseIfAnyMonitorHot) [{}] Skipping redundant sample (last sample {}ms ago, debounce: {}ms)",
                    Thread.currentThread().getName(), timeSinceLastSample, hotMonitoringIntervalMs);
                return;
            }

            // Sample monitors at checkpoint (chunk-driven, not continuous)
            monitoringCoordinator.sampleMonitors();
            lastMonitorSampleTime = now;

            // Decide whether to pause
            if (executionCoordinator.shouldPause()) {
                executionCoordinator.executePause();
            }
        }
    }

    /**
     * Check if paused and wait until resumed.
     * Control plane monitors for cooldown and triggers resume.
     *
     * CRITICAL: Increments pause count for ANY task that hits this checkpoint while paused.
     * This ensures fair treatment of all tasks regardless of timing or race conditions.
     */
    private void handlePauseCheckpoint(ChunkableTask<?> task) {
        if (executionCoordinator.getIsPaused()) {
            // Increment pause count for this task hitting checkpoint while paused
            task.incrementPauseCount();

            Instant pauseStart = Instant.now();

            LOGGER.debug("(handlePauseCheckpoint) [{}] Task {} paused at checkpoint (pause count: {})",
                Thread.currentThread().getName(), task.getTaskId(), task.getPauseCount());

            try {
                // Wait for control plane to resume (efficient wait/notify, no busy-waiting)
                executionCoordinator.awaitResume();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("(handlePauseCheckpoint) [{}] Task {} interrupted during pause",
                    Thread.currentThread().getName(), task.getTaskId());
            }

            long pauseDuration = Duration.between(pauseStart, Instant.now()).toMillis();
            totalPauseDuration.addAndGet(pauseDuration);
            LOGGER.debug("(handlePauseCheckpoint) [{}] Task {} resumed after {}ms pause",
                Thread.currentThread().getName(), task.getTaskId(), pauseDuration);
        }
    }


    /**
     * Check if task should be killed due to excessive pauses.
     */
    private void handleTerminationCondition(ChunkableTask<?> task) {
        if (config.isTaskTerminationEnabled() &&
            task.getPauseCount() > config.getMaxPauseCount()) {

            LOGGER.warn("(checkTerminationCondition) Terminating TASK: Task {} exceeded max pause count ({} > {})",
                task.getTaskId(), task.getPauseCount(), config.getMaxPauseCount());

            // Create exception
            TaskTerminatedException exception =
                new TaskTerminatedException(
                    task.getTaskId(),
                    task.getPauseCount(),
                    config.getMaxPauseCount()
                );

            // Notify task and decision maker
            task.onError(exception);
            executionCoordinator.recordKilledTask(task);
            tasksKilled.incrementAndGet();

            // Complete future exceptionally
            task.failTask(exception);

            LOGGER.info("(checkTerminationCondition) Task {} killed and added to killed tasks list", task.getTaskId());

            throw new RuntimeException(exception); // Exit execution
        }
    }

    private class WorkerThread implements Runnable {
        @Override
        public void run() {
            try {
                runExecutionLoop();
            } finally {
                // Ensure thread is not left in idleThreads if it exits unexpectedly
                idleThreads.remove(Thread.currentThread());
                if (workerLatch != null) {
                    workerLatch.countDown();
                }
            }
        }
    }
}
