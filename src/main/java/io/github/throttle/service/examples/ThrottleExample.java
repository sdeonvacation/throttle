package io.github.throttle.service.examples;

import io.github.throttle.service.api.*;
import io.github.throttle.service.base.AbstractChunkableTask;
import io.github.throttle.service.base.ExecutorMetrics;
import io.github.throttle.service.base.Priority;
import io.github.throttle.service.base.TaskTerminatedException;
import io.github.throttle.service.factory.ThrottleServiceFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating how to use the Throttle Service.
 */
public class ThrottleExample {

    public static void main(String[] args) throws Exception {
        // Create executor service with configuration
        ThrottleService executor = ThrottleServiceFactory.builder()
            .workerExecutorService(Executors.newFixedThreadPool(2))
            .controlPlaneExecutorService(Executors.newFixedThreadPool(1))
            .queueCapacity(100)               // Max 100 pending tasks
            .cpuMonitor(75, 45)               // Pause if CPU > 75%, resume if < 45%
            .memoryMonitor(70, 45)            // Pause if Memory > 70%, resume if < 45%
            .hysteresis(Duration.ofSeconds(10))  // 10s delay before state change
            .coldMonitoringInterval(Duration.ofSeconds(5))  // Check for cooldown every 5s when paused (default: 5s)
            .maxPauseCount(5)                 // Kill task after 5 pauses
            .taskTerminationEnabled(true)     // Enable task killing
            .starvationThreshold(Duration.ofHours(2))  // Boost priority after 2 hours waiting
            .build();
        System.out.println("Throttle Service started");

        // Create sample data
        List<String> highPriorityItems = createItems("HIGH", 100);
        List<String> mediumPriorityItems = createItems("MEDIUM", 100);
        List<String> lowPriorityItems = createItems("LOW", 100);

        // Submit tasks with different priorities
        System.out.println("\nSubmitting tasks...");

        Future<Void> highTask = executor.submit(
            new SampleTask(highPriorityItems, Priority.HIGH, 10)
        );

        Future<Void> mediumTask = executor.submit(
            new SampleTask(mediumPriorityItems, Priority.MEDIUM, 10)
        );

        Future<Void> lowTask = executor.submit(
            new SampleTask(lowPriorityItems, Priority.LOW, 10)
        );

        System.out.println("Tasks submitted");

        // Monitor progress
        while (!highTask.isDone() || !mediumTask.isDone() || !lowTask.isDone()) {
            ExecutorMetrics metrics = executor.getMetrics();
            System.out.println("\n--- Metrics ---");
            System.out.println("Active threads: " + metrics.getActiveThreads());
            System.out.println("Queue size: " + metrics.getQueueSize());
            System.out.println("Completed: " + metrics.getTasksCompleted());
            System.out.println("Failed: " + metrics.getTasksFailed());
            System.out.println("Killed: " + metrics.getTasksKilled());
            System.out.println("Paused: " + metrics.isPaused());
            System.out.println("Pause count: " + metrics.getPauseCount());
            System.out.println("Total pause duration: " + metrics.getTotalPauseDuration() + "ms");

            Thread.sleep(2000);
        }

        // Wait for completion
        highTask.get();
        mediumTask.get();
        lowTask.get();

        System.out.println("\n--- Final Metrics ---");
        ExecutorMetrics finalMetrics = executor.getMetrics();
        System.out.println("Total completed: " + finalMetrics.getTasksCompleted());
        System.out.println("Total failed: " + finalMetrics.getTasksFailed());
        System.out.println("Total killed: " + finalMetrics.getTasksKilled());
        System.out.println("Total pauses: " + finalMetrics.getPauseCount());
        System.out.println("Total pause duration: " + finalMetrics.getTotalPauseDuration() + "ms");

        // Check for killed tasks
        List<ChunkableTask<?>> killedTasks = executor.getKilledTasks();
        if (!killedTasks.isEmpty()) {
            System.out.println("\n--- Killed Tasks ---");
            for (ChunkableTask<?> task : killedTasks) {
                System.out.println("Task " + task.getTaskId() + " killed after " +
                                 task.getPauseCount() + " pauses");
            }
        }

        // Shutdown
        System.out.println("\nShutting down executor...");
        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);
        System.out.println("Executor shut down");
    }

    private static List<String> createItems(String prefix, int count) {
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            items.add(prefix + "-" + i);
        }
        return items;
    }

    /**
     * Sample task implementation.
     */
    static class SampleTask extends AbstractChunkableTask<String> {

        public SampleTask(List<String> items, Priority priority, int chunkSize) {
            super(items, priority, chunkSize);
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            System.out.println(Thread.currentThread().getName() +
                             " processing " + getPriority() + " task: " +
                             getTaskId() + ", chunk size: " + chunk.size());

            // Simulate processing
            for (String item : chunk) {
                // Do some work
                Thread.sleep(100);
            }

            System.out.println(Thread.currentThread().getName() +
                             " completed chunk for task: " + getTaskId());
        }

        @Override
        public void onComplete() {
            System.out.println("✓ Task " + getTaskId() + " (" + getPriority() +
                             ") completed successfully. Paused " + getPauseCount() + " times.");
        }

        @Override
        public void onError(Throwable error) {
            if (error instanceof TaskTerminatedException) {
                TaskTerminatedException killedException = (TaskTerminatedException) error;
                System.err.println("✗ Task " + getTaskId() + " (" + getPriority() +
                                 ") was KILLED after " + killedException.getPauseCount() +
                                 " pauses (max allowed: " + killedException.getMaxPauseCount() + ")");
            } else {
                System.err.println("✗ Task " + getTaskId() + " (" + getPriority() +
                                 ") failed: " + error.getMessage());
            }
        }

        @Override
        public void onCancel() {
            System.out.println("⊘ Task " + getTaskId() + " (" + getPriority() + ") was cancelled");
        }
    }
}

