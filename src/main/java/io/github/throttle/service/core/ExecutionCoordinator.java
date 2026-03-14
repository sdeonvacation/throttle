package io.github.throttle.service.core;

import io.github.throttle.service.api.ChunkableTask;
import io.github.throttle.service.base.Priority;
import io.github.throttle.service.config.ThrottleConfig;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Control plane for pause/resume and anti-starvation.
 * Runs continuous monitoring in background and uses wait/notify for efficient pause/resume.
 */
public class ExecutionCoordinator {
    private static final Logger LOGGER = Logger.getLogger(ExecutionCoordinator.class.getName());

    private final ThrottleConfig config;
    private final PriorityBlockingQueue<ChunkableTask<?>> priorityQueue;
    private final MonitoringCoordinator monitoringCoordinator;
    private final ExecutorService controlPlaneExecutorService;
    private final boolean ownsControlPlaneExecutorService;

    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicLong pauseCount = new AtomicLong(0);
    private final List<ChunkableTask<?>> killedTasks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // Set after construction to avoid circular dependency
    private volatile TaskExecutor taskExecutor;

    // Wait/notify mechanism for efficient pause/resume
    private final Lock pauseLock = new ReentrantLock();
    private final Condition resumeCondition = pauseLock.newCondition();


    public ExecutionCoordinator(ThrottleConfig config,
                                PriorityBlockingQueue<ChunkableTask<?>> priorityQueue,
                                MonitoringCoordinator monitoringCoordinator,
                                ExecutorService controlPlaneExecutorService) {
        this.config = config;
        this.priorityQueue = priorityQueue;
        this.monitoringCoordinator = monitoringCoordinator;

        // Use provided pool or create default
        if (controlPlaneExecutorService != null) {
            this.controlPlaneExecutorService = controlPlaneExecutorService;
            this.ownsControlPlaneExecutorService = false;
            LOGGER.info("ExecutionCoordinator using client-provided control plane pool");
        } else {
            this.controlPlaneExecutorService = createDefaultControlPlaneExecutorService();
            this.ownsControlPlaneExecutorService = true;
            LOGGER.info("ExecutionCoordinator created default control plane pool");
        }
    }


    /**
     * Wire in the TaskExecutor after construction (avoids circular constructor dependency).
     * Must be called before start().
     */
    public void setTaskExecutor(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    /**
     * Start control plane for resume monitoring.
     * Note: Pause detection is chunk-driven (handled by workers at checkpoints).
     * Control plane only monitors for resume while system is paused.
     */
    public void start() {
        controlPlaneExecutorService.submit(new ControlPlaneResumeMonitoringLoop());
        LOGGER.info("ExecutionCoordinator started - control plane will monitor for resume while paused");
    }

    /**
     * Control plane monitoring loop for RESUME detection only.
     * Only runs while paused to detect cooldown. Pause detection is chunk-driven.
     */
    private class ControlPlaneResumeMonitoringLoop implements Runnable {
        @Override
        public void run() {
            LOGGER.info("(ControlPlane) Resume monitoring loop started");
            long coldMonitoringIntervalMs = config.getColdMonitoringInterval().toMillis();

            while (!isShutdown.get() && !Thread.interrupted()) {
                try {
                    if (isPaused.get()) {
                        // Only sample monitors when paused (looking for cooldown)
                        monitoringCoordinator.sampleMonitors();

                        if (shouldResume()) {
                            executeResume();
                            LOGGER.info("(ControlPlane) Auto-resume triggered - all monitors cooled down");
                        }

                        Thread.sleep(coldMonitoringIntervalMs);
                    } else {
                        // When not paused, just sleep (workers handle pause detection at checkpoints)
                        Thread.sleep(coldMonitoringIntervalMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.info("(ControlPlane) Resume monitoring loop interrupted");
                    break;
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "(ControlPlane) Error in resume monitoring loop: " + e.getMessage(), e);
                }
            }

            LOGGER.info("(ControlPlane) Resume monitoring loop stopped");
        }
    }

    /**
     * Check if should pause based on monitoring state.
     */
    public boolean shouldPause() {
        return monitoringCoordinator.isAnyHot() && !isPaused.get();
    }

    /**
     * Check if should resume based on monitoring state.
     */
    public boolean shouldResume() {
        return monitoringCoordinator.isAllNormal() && isPaused.get();
    }

    /**
     * Execute pause.
     * Sets isPaused flag to signal workers to pause at their next checkpoint.
     *
     * Note: Individual task pause counts are NOT incremented here.
     * They are incremented in TaskExecutor.handlePauseCheckpoint() when each task
     * actually hits its checkpoint and pauses. This ensures ALL tasks that pause
     * are counted fairly, regardless of timing or race conditions.
     */
    public void executePause() {
        pauseLock.lock();
        try {
            if (isPaused.compareAndSet(false, true)) {
                pauseCount.incrementAndGet();
                LOGGER.info("(executePause) PAUSING SYSTEM. Resource pressure detected. All tasks will pause at their next checkpoint.");
                LOGGER.log(Level.FINE, "(executePause) Total system pause count: " + pauseCount.get());

                // Wake worker threads blocked in take() so they observe isPaused immediately
                // rather than waiting for the next task to arrive.
                if (taskExecutor != null) {
                    taskExecutor.interruptIdleWorkers();
                }
            }
        } finally {
            pauseLock.unlock();
        }
    }

    /**
     * Execute resume and notify all waiting threads.
     */
    public void executeResume() {
        pauseLock.lock();
        try {
            if (isPaused.compareAndSet(true, false)) {
                LOGGER.info("(executeResume) RESUMING ALL TASKS. Resources recovered to normal levels");
                LOGGER.log(Level.FINE, "(executeResume) Total pause events so far: " + pauseCount.get());

                // Notify ALL waiting threads to resume
                resumeCondition.signalAll();
            }
        } finally {
            pauseLock.unlock();
        }
    }

    /**
     * Wait until resumed. Called by worker threads at pause checkpoint.
     * Uses wait/notify instead of busy-waiting for efficiency.
     */
    public void awaitResume() throws InterruptedException {
        pauseLock.lock();
        try {
            while (isPaused.get() && !isShutdown.get()) {
                resumeCondition.await();
            }
        } finally {
            pauseLock.unlock();
        }
    }

    /**
     * Manual pause - pauses all tasks at their next checkpoint.
     */
    public void pauseAll() {
        executePause();
    }

    /**
     * Manual resume.
     */
    public void resumeAll() {
        executeResume();
    }

    /**
     * Check if currently paused.
     */
    public boolean getIsPaused() {
        return isPaused.get();
    }

    /**
     * Get pause count.
     */
    public long getPauseCount() {
        return pauseCount.get();
    }

    /**
     * Record a killed task.
     */
    public void recordKilledTask(ChunkableTask<?> task) {
        killedTasks.add(task);
    }

    /**
     * Get list of killed tasks.
     */
    public List<ChunkableTask<?>> getKilledTasks() {
        return new ArrayList<>(killedTasks);
    }


    /**
     * Check for starved tasks and boost their priority.
     * Called after task completion to check if any waiting tasks need priority boost.
     */
    public void checkStarvation() {
        Instant now = Instant.now();
        Duration threshold = config.getStarvationThreshold();

        for (ChunkableTask<?> task : priorityQueue) {
            // Synchronize on the task to prevent concurrent boosts
            synchronized (task) {
                // Calculate wait time since enqueue or last boost
                Instant referenceTime = task.getLastBoostTime() != null ?
                        task.getLastBoostTime() : task.getEnqueueTime();
                Duration waitTime = Duration.between(referenceTime, now);

                // Check if task has been waiting longer than threshold
                if (waitTime.compareTo(threshold) >= 0) {
                    Priority currentPriority = task.getCurrentPriority();
                    Priority boostedPriority = currentPriority.boost();

                    // Only boost if priority actually changed
                    if (boostedPriority != currentPriority) {
                        task.setCurrentPriority(boostedPriority);
                        task.setLastBoostTime(now);

                        LOGGER.log(Level.INFO, "(checkStarvation) Boosted task " + task.getTaskId() + " from " + currentPriority + " to " + boostedPriority + " (waited " + waitTime.toMinutes() + " minutes)");

                        // Re-sort queue to reflect new priority
                        priorityQueue.remove(task);
                        priorityQueue.offer(task);
                    }
                }
            }
        }
    }

    /**
     * Shutdown decision maker.
     */
    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            LOGGER.info("Shutting down ExecutionCoordinator");

            // Wake up any waiting threads
            pauseLock.lock();
            try {
                resumeCondition.signalAll();
            } finally {
                pauseLock.unlock();
            }

            if (ownsControlPlaneExecutorService) {
                controlPlaneExecutorService.shutdown();
            }
        }
    }

    public void shutdownNow() {
        shutdown();
        if (ownsControlPlaneExecutorService) {
            controlPlaneExecutorService.shutdownNow();
        }
    }

    private ExecutorService createDefaultControlPlaneExecutorService() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Throttle-Control-Plane");
            t.setDaemon(true);
            return t;
        });
    }
}


