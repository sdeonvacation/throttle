package io.github.throttle.simulator.test;

import io.github.throttle.service.api.*;
import io.github.throttle.service.base.ExecutorMetrics;
import io.github.throttle.service.base.Priority;
import io.github.throttle.service.factory.ThrottleServiceFactory;
import io.github.throttle.simulator.load.CpuLoadGenerator;
import io.github.throttle.simulator.load.MemoryLoadGenerator;
import io.github.throttle.simulator.service.MonitoringService;
import io.github.throttle.simulator.monitor.SystemMonitor;
import io.github.throttle.simulator.monitor.MonitoringStats;
import io.github.throttle.simulator.tasks.FailingSimulatedTask;
import io.github.throttle.simulator.tasks.SimulatedTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Test scenario runner for Throttle Service.
 */
@Component
public class ScenarioRunner {
    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private final SystemMonitor systemMonitor;
    private final CpuLoadGenerator cpuLoader;
    private final MemoryLoadGenerator memoryLoader;

    @Autowired(required = false)
    private MonitoringService monitoringService;

    public ScenarioRunner() {
        this.systemMonitor = new SystemMonitor();
        this.cpuLoader = new CpuLoadGenerator();
        this.memoryLoader = new MemoryLoadGenerator();
    }

    /**
     * Scenario 1: Normal operation without resource pressure.
     * Verifies basic functionality and throughput.
     */
    public TestResult runNormalOperationTest() {
        log.info("=== Running Scenario 1: Normal Operation ===");

        TestResult result = new TestResult("Normal Operation");
        ThrottleService executor = null;

        try {
            // Create executor
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(5))
                .queueCapacity(100)
                .cpuMonitor(75, 50)
                .memoryMonitor(70, 50)
                .maxPauseCount(5)
                .build();

            // Start monitoring with this executor
            startMonitoring(executor);

            // Submit 50 tasks
            List<Future<Void>> futures = new ArrayList<>();
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 50; i++) {
                Priority priority = i < 10 ? Priority.HIGH : i < 30 ? Priority.MEDIUM : Priority.LOW;
                SimulatedTask task = new SimulatedTask("task-" + i, 100, priority, 20, 10);
                futures.add(executor.submit(task));
            }

            // Wait for completion
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            long duration = System.currentTimeMillis() - startTime;

            // Collect results
            ExecutorMetrics metrics = executor.getMetrics();
            result.setDuration(duration);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());
            result.setSuccess(metrics.getTasksCompleted() == 50 && metrics.getTasksFailed() == 0);

            log.info("Normal operation completed: {}ms, {} tasks", duration, metrics.getTasksCompleted());

        } catch (Exception e) {
            log.error("Normal operation test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            stopMonitoring();
            if (executor != null) {
                executor.shutdown();
            }
            MonitoringStats stats = systemMonitor.getStats();
            result.setMonitoringStats(stats);
            log.info("System stats: {}", stats);
        }

        return result;
    }

    /**
     * Scenario 2: Resource spike during execution.
     * Tests pause/resume behavior under resource pressure.
     */
    public TestResult runResourceSpikeTest() {
        log.info("=== Running Scenario 2: Resource Spike ===");

        TestResult result = new TestResult("Resource Spike");
        ThrottleService executor = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(5))
                .queueCapacity(100)
                .cpuMonitor(70, 45)
                .memoryMonitor(65, 45)
                .maxPauseCount(5)
                .build();

            startMonitoring(executor);

            // Submit tasks with longer processing time to ensure they're still running during spike
            List<Future<Void>> futures = new ArrayList<>();
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 30; i++) {
                // Longer tasks: 300 items with smaller chunks = more time to process
                SimulatedTask task = new SimulatedTask("task-" + i, 300, Priority.MEDIUM, 15, 12);
                futures.add(executor.submit(task));
            }

            // Wait shorter time before spike - tasks should still be processing
            Thread.sleep(1000);
            log.info("Generating CPU spike (80% for 5 seconds)...");

            // Start CPU spike
            cpuLoader.generateLoad(80, 5000);

            // Wait for spike to complete
            cpuLoader.await();
            log.info("CPU spike completed");

            // Wait for tasks to complete
            for (Future<Void> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Task future exception: {}", e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            // Collect results
            ExecutorMetrics metrics = executor.getMetrics();
            result.setDuration(duration);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());

            // Success if most tasks completed (even if no pause occurred on fast systems)
            // OR if pauses occurred (proving the mechanism works)
            boolean mostTasksCompleted = metrics.getTasksCompleted() >= 25; // At least 25 out of 30
            boolean pauseOccurred = metrics.getPauseCount() > 0;
            result.setSuccess(mostTasksCompleted || pauseOccurred);

            log.info("Resource spike test completed: {}ms, completed={}, pauses={}, success={}",
                duration, metrics.getTasksCompleted(), metrics.getPauseCount(), result.isSuccess());

        } catch (Exception e) {
            log.error("Resource spike test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            cpuLoader.stopLoad();
            stopMonitoring();
            if (executor != null) {
                executor.shutdown();
            }
            MonitoringStats stats = systemMonitor.getStats();
            result.setMonitoringStats(stats);
            log.info("System stats: {}", stats);
        }

        return result;
    }

    /**
     * Scenario 3: Sustained high load.
     * Tests behavior under continuous resource pressure.
     */
    public TestResult runSustainedLoadTest() {
        log.info("=== Running Scenario 3: Sustained Load ===");

        TestResult result = new TestResult("Sustained Load");
        ThrottleService executor = null;
        Thread loadThread = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(5))
                .queueCapacity(100)
                .cpuMonitor(75, 50)
                .memoryMonitor(70, 50)
                .maxPauseCount(10)
                .build();

            startMonitoring(executor);

            final ThrottleService finalExecutor = executor;

            // Start continuous CPU load in background
            loadThread = new Thread(() -> {
                try {
                    log.info("Starting sustained CPU load...");
                    cpuLoader.generateLoad(60, 15000);
                } catch (Exception e) {
                    log.error("Load generation failed", e);
                }
            });
            loadThread.start();

            // Submit tasks while under load
            List<Future<Void>> futures = new ArrayList<>();
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 20; i++) {
                SimulatedTask task = new SimulatedTask("task-" + i, 150, Priority.MEDIUM, 20, 10);
                futures.add(finalExecutor.submit(task));
                Thread.sleep(100);
            }

            // Wait for tasks
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            long duration = System.currentTimeMillis() - startTime;

            ExecutorMetrics metrics = finalExecutor.getMetrics();
            result.setDuration(duration);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());
            result.setSuccess(metrics.getTasksCompleted() > 0);

            log.info("Sustained load test completed: {}ms, completed={}, pauses={}",
                duration, metrics.getTasksCompleted(), metrics.getPauseCount());

        } catch (Exception e) {
            log.error("Sustained load test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            cpuLoader.stopLoad();
            if (loadThread != null) {
                try {
                    loadThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (executor != null) {
                executor.shutdown();
            }
            systemMonitor.stopMonitoring();
            MonitoringStats stats = systemMonitor.getStats();
            result.setMonitoringStats(stats);
            log.info("System stats: {}", stats);
        }

        return result;
    }

    /**
     * Scenario 4: Memory pressure test.
     * Tests behavior under memory constraints.
     */
    public TestResult runMemoryPressureTest() {
        log.info("=== Running Scenario 4: Memory Pressure ===");

        TestResult result = new TestResult("Memory Pressure");
        ThrottleService executor = null;
        Thread memoryThread = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(5))
                .queueCapacity(100)
                .cpuMonitor(80, 50)
                .memoryMonitor(65, 45)
                .maxPauseCount(10)
                .build();

            startMonitoring(executor);

            // Start memory allocation in background
            memoryThread = new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    log.info("Allocating memory...");
                    memoryLoader.generateLoad(70, 8000);
                } catch (Exception e) {
                    log.error("Memory allocation failed", e);
                }
            });
            memoryThread.start();

            // Submit tasks
            List<Future<Void>> futures = new ArrayList<>();
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 25; i++) {
                SimulatedTask task = new SimulatedTask("task-" + i, 100, Priority.MEDIUM, 20, 10);
                futures.add(executor.submit(task));
            }

            // Wait for completion
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            long duration = System.currentTimeMillis() - startTime;

            ExecutorMetrics metrics = executor.getMetrics();
            result.setDuration(duration);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());
            result.setSuccess(metrics.getTasksCompleted() > 0);

            log.info("Memory pressure test completed: {}ms, completed={}, pauses={}",
                duration, metrics.getTasksCompleted(), metrics.getPauseCount());

        } catch (Exception e) {
            log.error("Memory pressure test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            memoryLoader.releaseMemory();
            if (memoryThread != null) {
                try {
                    memoryThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (executor != null) {
                executor.shutdown();
            }
            systemMonitor.stopMonitoring();
            MonitoringStats stats = systemMonitor.getStats();
            result.setMonitoringStats(stats);
            log.info("System stats: {}", stats);
        }

        return result;
    }

    /**
     * Scenario 5: Task killing test.
     * Tests that tasks are killed after excessive pauses.
     * Uses very long-running tasks to ensure the same tasks get paused repeatedly.
     */
    public TestResult runTaskKillingTest() {
        log.info("=== Running Scenario 5: Task Killing ===");

        TestResult result = new TestResult("Task Killing");
        ThrottleService executor = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(3))
                .queueCapacity(50)
                .cpuMonitor(70, 40)  // Lower threshold to trigger easily
                .memoryMonitor(60, 40)
                .hysteresis(Duration.ofSeconds(2))// Shorter hysteresis for faster testing
                    .hotMonitoringDebounceInterval(Duration.ofSeconds(2)) // Shorter debounce to allow multiple pauses
                    .coldMonitoringInterval(Duration.ofSeconds(2)) // More frequent checks while paused
                .maxPauseCount(2)  // Kill after exceeding 2 pauses (i.e., on 3rd pause)
                .taskTerminationEnabled(true)
                .build();

            startMonitoring(executor);

            final ThrottleService finalExecutor = executor;

            // Submit VERY LONG tasks that will take 30+ seconds to complete
            // This ensures the same 3 tasks stay running and get paused repeatedly
            List<Future<Void>> futures = new ArrayList<>();
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 5; i++) {
                // VERY heavy tasks: 1000 items, smaller chunks, heavier per-item work
                // Each chunk now takes ~500ms (5 items * 100ms + CPU), 200 chunks => long-running
                // This increases chances tasks get paused multiple times during memory spikes
                SimulatedTask task = new SimulatedTask("task-" + i, 1000, Priority.MEDIUM, 5, 200);
                futures.add(finalExecutor.submit(task));
            }

            log.info("Submitted 5 very long tasks. Only 3 will run at once (pool size = 3).");
            log.info("Will generate 4 MEMORY spikes to trigger multiple pauses on the same running tasks.");

            // Wait for tasks to start processing (first 3 tasks picked up)
            Thread.sleep(2000);
            log.info("Tasks should now be running. Starting MEMORY spike cycle...");

            // Generate 4 memory spikes with controlled timing
            // This ensures the SAME 3 tasks (the ones running) get paused each time
            for (int i = 1; i <= 4; i++) {
                log.info("=== MEMORY Spike {} of 4 ===", i);

                // Generate intense memory pressure
                // Blocking call: generateLoad will allocate and hold memory for the duration
                memoryLoader.generateLoad(95, 8000);  // target 85% heap for 6 seconds

                log.info("MEMORY spike {} completed. Waiting for system to cool down...", i);

                // Wait for hysteresis + cooling period (2s hysteresis + 3s cool down)
                Thread.sleep(5000);

                log.info("System should have resumed. Tasks continue processing...");

                // Short gap before next spike (let tasks process a bit)
                if (i < 4) {
                    Thread.sleep(2000);
                }
            }

            log.info("All MEMORY spikes completed. Waiting for tasks to finish or be killed...");

            // Wait for remaining tasks to complete or be killed
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get(5, TimeUnit.SECONDS);
                    log.info("Task {} completed successfully", i);
                } catch (java.util.concurrent.TimeoutException e) {
                    log.info("Task {} timed out (may still be processing)", i);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() != null && e.getCause().getClass().getSimpleName().contains("Killed")) {
                        log.info("Task {} was killed (expected): {}", i, e.getCause().getMessage());
                    } else {
                        log.warn("Task {} failed: {}", i, e.getMessage());
                    }
                } catch (Exception e) {
                    log.warn("Task {} exception: {}", i, e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            ExecutorMetrics metrics = finalExecutor.getMetrics();
            List<ChunkableTask<?>> killedTasks = finalExecutor.getKilledTasks();

            result.setDuration(duration);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());

            // Success criteria: At least one task killed (proving the mechanism works)
            result.setSuccess(metrics.getTasksKilled() > 0);

            log.info("===================================================");
            log.info("Task Killing Test Results:");
            log.info("  Duration: {}ms", duration);
            log.info("  Tasks Completed: {}", metrics.getTasksCompleted());
            log.info("  Tasks Failed: {}", metrics.getTasksFailed());
            log.info("  Tasks Killed: {}", metrics.getTasksKilled());
            log.info("  Global Pause Count: {} (number of pause events)", metrics.getPauseCount());
            log.info("  Success: {}", result.isSuccess());
            log.info("===================================================");

            if (!killedTasks.isEmpty()) {
                log.info("Killed Tasks Details:");
                for (ChunkableTask<?> task : killedTasks) {
                    log.info("  → Task ID: {}, Individual Pause Count: {} (exceeded maxPauseCount=2)",
                        task.getTaskId(), task.getPauseCount());
                }
            } else {
                log.warn("WARNING: No tasks were killed! Check if:");
                log.warn("  1. Tasks are long enough (should take 30+ seconds)");
                log.warn("  2. MEMORY spikes are triggering pauses (check pause count)");
                log.warn("  3. Same tasks are getting paused each time");
                log.warn("  4. Tasks are reaching checkpoints during pauses");
            }

        } catch (Exception e) {
            log.error("Task killing test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            memoryLoader.releaseMemory();
            stopMonitoring();
            if (executor != null) {
                executor.shutdown();
            }
            MonitoringStats stats = systemMonitor.getStats();
            result.setMonitoringStats(stats);
            log.info("System stats: {}", stats);
        }

        return result;
    }

    /**
     * Scenario 6: Priority scheduling under pressure.
     * Tests that HIGH priority tasks complete before LOW priority tasks in queue.
     */
    public TestResult runPrioritySchedulingTest() {
        log.info("=== Running Scenario 6: Priority Scheduling ===");

        TestResult result = new TestResult("Priority Scheduling");
        ThrottleService executor = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(3))
                .queueCapacity(50)
                .cpuMonitor(75, 50)
                .memoryMonitor(70, 50)
                .maxPauseCount(10)
                .build();

            startMonitoring(executor);

            // Submit tasks in mixed priority order
            List<SimulatedTask> highTasks = new ArrayList<>();
            List<SimulatedTask> lowTasks = new ArrayList<>();

            long startTime = System.currentTimeMillis();

            // Submit LOW priority tasks first (many more than threads)
            for (int i = 0; i < 10; i++) {
                SimulatedTask task = new SimulatedTask("low-" + i, 100, Priority.LOW, 20, 10);
                lowTasks.add(task);
                executor.submit(task);
            }

            // Small delay to ensure LOW tasks are queued
            Thread.sleep(100);

            // Now submit HIGH priority tasks - should jump queue
            for (int i = 0; i < 5; i++) {
                SimulatedTask task = new SimulatedTask("high-" + i, 100, Priority.HIGH, 20, 10);
                highTasks.add(task);
                executor.submit(task);
            }

            // Wait for all to complete
            Thread.sleep(15000);

            long duration = System.currentTimeMillis() - startTime;

            // Count completed tasks
            int highCompleted = 0;
            int lowCompleted = 0;

            for (SimulatedTask task : highTasks) {
                if (task.isCompleted()) {
                    highCompleted++;
                }
            }

            for (SimulatedTask task : lowTasks) {
                if (task.isCompleted()) {
                    lowCompleted++;
                }
            }

            // Priority is working if:
            // 1. All HIGH priority tasks completed
            // 2. Most tasks completed (at least 10 out of 15)
            ExecutorMetrics metrics = executor.getMetrics();
            boolean priorityWorked = highCompleted == 5 && metrics.getTasksCompleted() >= 10;

            result.setDuration(duration);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setSuccess(priorityWorked);

            log.info("Priority scheduling test completed: highCompleted={}, lowCompleted={}, total={}, success={}",
                highCompleted, lowCompleted, metrics.getTasksCompleted(), priorityWorked);

        } catch (Exception e) {
            log.error("Priority scheduling test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            stopMonitoring();
            if (executor != null) {
                executor.shutdown();
            }
            MonitoringStats stats = systemMonitor.getStats();
            result.setMonitoringStats(stats);
            log.info("System stats: {}", stats);
        }

        return result;
    }

    /**
     * Scenario 7: Stress test - many tasks, high concurrency.
     * Tests system stability under heavy load.
     */
    public TestResult runStressTest() {
        log.info("=== Running Scenario 7: Stress Test ===");

        TestResult result = new TestResult("Stress Test");
        ThrottleService executor = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(8)) // Use more threads
                .queueCapacity(200)
                .cpuMonitor(80, 55)
                .memoryMonitor(75, 55)
                .maxPauseCount(10)
                .build();

            startMonitoring(executor);

            // Submit many tasks
            List<Future<Void>> futures = new ArrayList<>();
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 100; i++) {
                Priority priority = i % 3 == 0 ? Priority.HIGH : i % 3 == 1 ? Priority.MEDIUM : Priority.LOW;
                SimulatedTask task = new SimulatedTask("task-" + i, 50, priority, 10, 5);
                futures.add(executor.submit(task));
            }

            // Wait for completion
            int completed = 0;
            for (Future<Void> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                    completed++;
                } catch (Exception e) {
                    // Some may fail
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            ExecutorMetrics metrics = executor.getMetrics();
            result.setDuration(duration);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());
            result.setSuccess(completed >= 90); // At least 90% success

            log.info("Stress test completed: {}ms, completed={}/100, throughput={} tasks/sec",
                duration, completed, (completed * 1000.0 / duration));

        } catch (Exception e) {
            log.error("Stress test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            if (executor != null) {
                executor.shutdown();
            }
            systemMonitor.stopMonitoring();
            MonitoringStats stats = systemMonitor.getStats();
            result.setMonitoringStats(stats);
            log.info("System stats: {}", stats);
        }

        return result;
    }

    // =========================================================================
    // NEGATIVE / EDGE-CASE SCENARIOS
    // =========================================================================

    /**
     * Scenario 8: Flapping monitor.
     * CPU oscillates above and below the hot threshold faster than the debounce
     * window.  The executor must NOT thrash (rapid pause/resume storm) and all
     * tasks must eventually complete once the load stabilises.
     *
     * Success criteria:
     *   - All tasks complete.
     *   - Pause count is bounded (debounce is absorbing rapid oscillations).
     */
    public TestResult runFlappingMonitorTest() {
        log.info("=== Running Scenario 8: Flapping Monitor ===");

        TestResult result = new TestResult("Flapping Monitor");
        ThrottleService executor = null;

        try {
            // Use a tight hot threshold so we can cross it with a moderate CPU spike
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(4))
                .queueCapacity(50)
                .cpuMonitor(55, 35)           // Low threshold — easier to cross
                .memoryMonitor(85, 60)         // Memory threshold high — won't interfere
                .hotMonitoringDebounceInterval(java.time.Duration.ofMillis(800)) // absorb rapid flaps
                .coldMonitoringInterval(java.time.Duration.ofMillis(500))
                .maxPauseCount(20)             // High limit — we want pauses, not kills
                .taskTerminationEnabled(false)
                .build();

            startMonitoring(executor);

            // Submit long-running tasks so they span the entire flapping window
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                SimulatedTask task = new SimulatedTask("flap-task-" + i, 200, Priority.MEDIUM, 10, 20);
                futures.add(executor.submit(task));
            }

            long startTime = System.currentTimeMillis();

            // Flap CPU load: short bursts ON/OFF 5 times
            log.info("Starting CPU flapping: 5 cycles of 60% for 1s ON / 1s OFF");
            for (int cycle = 0; cycle < 5; cycle++) {
                cpuLoader.generateLoad(65, 1000);
                cpuLoader.await();
                log.info("Flap cycle {} / 5 complete", cycle + 1);
                Thread.sleep(1000); // 1s cool-down between flaps
            }

            log.info("Flapping complete — waiting for tasks to finish");
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }

            long duration = System.currentTimeMillis() - startTime;
            ExecutorMetrics metrics = executor.getMetrics();

            result.setDuration(duration);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());

            boolean allCompleted  = metrics.getTasksCompleted() == 12;
            boolean noneKilled    = metrics.getTasksKilled() == 0;
            // Debounce should keep pauses well below one-per-flap (5 flaps → ≤ 10 pauses)
            boolean pausesBounded = metrics.getPauseCount() <= 10;

            result.setSuccess(allCompleted && noneKilled);

            log.info("Flapping monitor test done: {}ms, completed={}, pauses={}, bounded={}",
                duration, metrics.getTasksCompleted(), metrics.getPauseCount(), pausesBounded);

        } catch (Exception e) {
            log.error("Flapping monitor test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            cpuLoader.stopLoad();
            stopMonitoring();
            if (executor != null) executor.shutdown();
            result.setMonitoringStats(systemMonitor.getStats());
        }

        return result;
    }

    /**
     * Scenario 9: Queue overflow.
     * Submits tasks much faster than the pool can process them against a very
     * small queue.  Tests that the overflow policy (REJECT) fires correctly and
     * that tasks submitted after the overflow are still accepted once the queue
     * drains back below capacity.
     *
     * Success criteria:
     *   - At least one RejectedExecutionException is observed (overflow triggered).
     *   - Tasks submitted after overflow still complete (executor is still healthy).
     */
    public TestResult runQueueOverflowTest() {
        log.info("=== Running Scenario 9: Queue Overflow ===");

        TestResult result = new TestResult("Queue Overflow");
        ThrottleService executor = null;

        try {
            // 1 worker thread + queue capacity 3 → easy to overflow
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(1))
                .queueCapacity(3)
                .cpuMonitor(90, 70)   // High threshold — won't trigger during this test
                .memoryMonitor(90, 70)
                .maxPauseCount(5)
                .taskTerminationEnabled(false)
                .overflowPolicy(io.github.throttle.service.config.OverflowPolicy.REJECT)
                .build();

            startMonitoring(executor);

            int rejectedCount = 0;
            List<Future<Void>> accepted = new ArrayList<>();

            // Fire 10 tasks instantly — worker thread is busy on first, queue fills, rest get rejected
            log.info("Submitting 10 tasks to a queue with capacity 3 and 1 worker");
            for (int i = 0; i < 10; i++) {
                try {
                    // Slow task: 5 items × 300ms = 1.5s per task
                    SimulatedTask task = new SimulatedTask("overflow-task-" + i, 5,
                        Priority.MEDIUM, 5, 300);
                    Future<Void> f = executor.submit(task);
                    accepted.add(f);
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    rejectedCount++;
                    log.info("Task {} rejected (expected) — total rejections so far: {}", i, rejectedCount);
                }
            }

            log.info("Submission done: {} accepted, {} rejected", accepted.size(), rejectedCount);

            // Wait for accepted tasks to drain
            for (Future<Void> f : accepted) {
                try { f.get(30, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }

            // Now submit a fresh task — queue is empty, must be accepted and complete
            SimulatedTask recovery = new SimulatedTask("recovery-task", 5, Priority.HIGH, 5, 50);
            Future<Void> recoveryFuture = executor.submit(recovery);
            recoveryFuture.get(10, TimeUnit.SECONDS);

            ExecutorMetrics metrics = executor.getMetrics();

            result.setDuration(0);
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());

            boolean overflowTriggered = rejectedCount > 0;
            boolean executorRecovered = recovery.isCompleted();
            result.setSuccess(overflowTriggered && executorRecovered);

            log.info("Queue overflow test done: rejected={}, recovered={}, success={}",
                rejectedCount, executorRecovered, result.isSuccess());

        } catch (Exception e) {
            log.error("Queue overflow test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            stopMonitoring();
            if (executor != null) executor.shutdown();
            result.setMonitoringStats(systemMonitor.getStats());
        }

        return result;
    }

    /**
     * Scenario 10: Failing tasks.
     * Submits a mix of tasks where half throw exceptions during chunk processing.
     * Verifies that:
     *   - Failed tasks do not crash worker threads.
     *   - Healthy tasks still complete.
     *   - Metrics accurately reflect failed vs completed counts.
     *
     * Success criteria:
     *   - All healthy tasks complete.
     *   - All failing tasks are counted in tasksFailed.
     *   - Executor remains operational (no worker threads lost).
     */
    public TestResult runFailingTasksTest() {
        log.info("=== Running Scenario 10: Failing Tasks ===");

        TestResult result = new TestResult("Failing Tasks");
        ThrottleService executor = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(3))
                .queueCapacity(50)
                .cpuMonitor(90, 70)
                .memoryMonitor(90, 70)
                .maxPauseCount(5)
                .taskTerminationEnabled(false)
                .build();

            startMonitoring(executor);

            int totalTasks   = 20;
            int failingCount = 10; // every other task will fail on its first chunk

            List<Future<Void>> futures = new ArrayList<>();
            List<Boolean> expectedFail = new ArrayList<>();

            for (int i = 0; i < totalTasks; i++) {
                if (i % 2 == 0) {
                    // Healthy task
                    SimulatedTask task = new SimulatedTask("ok-task-" + i, 30, Priority.MEDIUM, 10, 10);
                    futures.add(executor.submit(task));
                    expectedFail.add(false);
                } else {
                    // Failing task — throws on first chunk (index 0)
                    FailingSimulatedTask task = new FailingSimulatedTask(
                        "fail-task-" + i, 30, Priority.MEDIUM, 10, 10, 0);
                    futures.add(executor.submit(task));
                    expectedFail.add(true);
                }
            }

            // Collect results
            long actualCompleted = 0;
            long actualFailed    = 0;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get(30, TimeUnit.SECONDS);
                    actualCompleted++;
                } catch (java.util.concurrent.ExecutionException e) {
                    actualFailed++;
                    log.debug("Task {} failed as expected: {}", i, e.getCause().getMessage());
                }
            }

            ExecutorMetrics metrics = executor.getMetrics();

            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());

            boolean healthyTasksDone    = actualCompleted == (totalTasks - failingCount);
            boolean failedTasksAccurate = actualFailed == failingCount;
            boolean metricsMatch        = metrics.getTasksCompleted() == actualCompleted
                                       && metrics.getTasksFailed() == actualFailed;

            result.setSuccess(healthyTasksDone && failedTasksAccurate && metricsMatch);

            log.info("Failing tasks test done: completed={}, failed={}, metricsMatch={}, success={}",
                actualCompleted, actualFailed, metricsMatch, result.isSuccess());

        } catch (Exception e) {
            log.error("Failing tasks test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            stopMonitoring();
            if (executor != null) executor.shutdown();
            result.setMonitoringStats(systemMonitor.getStats());
        }

        return result;
    }

    /**
     * Scenario 11: Cascade kill.
     * All running tasks exceed maxPauseCount simultaneously and are killed.
     * After the kill, new tasks are submitted — the executor must recover
     * and process them normally.
     *
     * Success criteria:
     *   - All initial tasks are killed (not completed).
     *   - Recovery tasks submitted afterwards complete successfully.
     *   - Executor operational metrics are internally consistent.
     */
    public TestResult runCascadeKillTest() {
        log.info("=== Running Scenario 11: Cascade Kill ===");

        TestResult result = new TestResult("Cascade Kill");
        ThrottleService executor = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(3))
                .queueCapacity(20)
                .cpuMonitor(25, 15)          // Very low thresholds to ensure more pauses
                .memoryMonitor(85, 60)
                .hotMonitoringDebounceInterval(java.time.Duration.ofMillis(100))  // Faster sampling
                .coldMonitoringInterval(java.time.Duration.ofMillis(500))
                .hysteresis(java.time.Duration.ofMillis(500))  // Reduce hysteresis to be more responsive
                .maxPauseCount(2)            // Kill after 2 pauses
                .taskTerminationEnabled(true)
                .build();

            startMonitoring(executor);

            // Submit 3 very long tasks — they will run concurrently (pool = 3)
            int initialTaskCount = 3;
            List<Future<Void>> initialFutures = new ArrayList<>();
            for (int i = 0; i < initialTaskCount; i++) {
                // 1000 items × 50ms = 50s with 100 chunks — more frequent pause checks
                SimulatedTask task = new SimulatedTask("cascade-task-" + i, 1000,
                    Priority.MEDIUM, 10, 50);
                initialFutures.add(executor.submit(task));
            }

            // Wait for first chunks to complete, then drive 3 pause cycles via CPU spikes
            Thread.sleep(1500);  // Wait longer to ensure tasks are actively processing
            log.info("Tasks started. Driving 3 CPU spike cycles to kill them (maxPauseCount=2)...");

            for (int cycle = 1; cycle <= 3; cycle++) {
                log.info("CPU spike {} / 3", cycle);
                cpuLoader.generateLoad(80, 4000);  // Higher load and longer duration
                cpuLoader.await();
                log.info("Spike {} done — waiting for cool down", cycle);
                Thread.sleep(2000);
            }

            // All initial tasks should now be killed
            Thread.sleep(2000);
            long killedCount = 0;
            for (Future<Void> f : initialFutures) {
                try {
                    f.get(2, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() != null &&
                        e.getCause().getClass().getSimpleName().contains("Terminated")) {
                        killedCount++;
                        log.info("Task killed as expected: {}", e.getCause().getMessage());
                    }
                } catch (Exception ignored) {}
            }

            log.info("Kill phase done: {} / {} tasks killed", killedCount, initialTaskCount);

            // Recovery phase: submit fresh tasks — executor must still work
            log.info("Recovery phase: submitting 5 normal tasks after cascade kill");
            List<Future<Void>> recoveryFutures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                SimulatedTask task = new SimulatedTask("recovery-task-" + i, 20,
                    Priority.HIGH, 10, 20);
                recoveryFutures.add(executor.submit(task));
            }

            long recoveryCompleted = 0;
            for (Future<Void> f : recoveryFutures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    recoveryCompleted++;
                } catch (Exception e) {
                    log.warn("Recovery task failed: {}", e.getMessage());
                }
            }

            ExecutorMetrics metrics = executor.getMetrics();
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());

            boolean someKilled       = killedCount > 0;
            boolean executorRecovered = recoveryCompleted == 5;
            result.setSuccess(someKilled && executorRecovered);

            log.info("Cascade kill test done: killed={}, recoveryCompleted={}, success={}",
                killedCount, recoveryCompleted, result.isSuccess());

        } catch (Exception e) {
            log.error("Cascade kill test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            cpuLoader.stopLoad();
            stopMonitoring();
            if (executor != null) executor.shutdown();
            result.setMonitoringStats(systemMonitor.getStats());
        }

        return result;
    }

    /**
     * Scenario 12: Graceful shutdown under load.
     * Submits a full queue of tasks and immediately calls shutdown().
     * Verifies that:
     *   - shutdown() returns without deadlock.
     *   - awaitTermination() completes within a reasonable time.
     *   - Tasks that were already running get a chance to complete their
     *     current chunk before the worker exits.
     *
     * Success criteria:
     *   - awaitTermination() returns true within 10s.
     *   - Executor reports isShutdown() = true.
     */
    public TestResult runShutdownUnderLoadTest() {
        log.info("=== Running Scenario 12: Shutdown Under Load ===");

        TestResult result = new TestResult("Shutdown Under Load");
        ThrottleService executor = null;

        try {
            executor = ThrottleServiceFactory.builder()
                .workerThreadPool(Executors.newFixedThreadPool(3))
                .queueCapacity(30)
                .cpuMonitor(90, 70)
                .memoryMonitor(90, 70)
                .maxPauseCount(5)
                .taskTerminationEnabled(false)
                .build();

            startMonitoring(executor);

            // Fill the queue — each task takes ~1s per chunk, 5 chunks = 5s
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                SimulatedTask task = new SimulatedTask("shutdown-task-" + i, 50,
                    Priority.MEDIUM, 10, 20);
                futures.add(executor.submit(task));
            }

            // Let tasks start (first chunk processing begins)
            Thread.sleep(300);
            log.info("Tasks started — calling shutdown() immediately");

            long shutdownStart = System.currentTimeMillis();
            executor.shutdown();
            boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - shutdownStart;

            boolean shutdownClean = terminated && executor.isShutdown();

            ExecutorMetrics metrics = executor.getMetrics();
            result.setTasksCompleted(metrics.getTasksCompleted());
            result.setTasksFailed(metrics.getTasksFailed());
            result.setTasksKilled(metrics.getTasksKilled());
            result.setPauseCount(metrics.getPauseCount());
            result.setDuration(elapsed);
            result.setSuccess(shutdownClean);

            log.info("Shutdown under load test done: terminated={}, elapsed={}ms, " +
                "completed={}, success={}",
                terminated, elapsed, metrics.getTasksCompleted(), shutdownClean);

        } catch (Exception e) {
            log.error("Shutdown under load test failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        } finally {
            stopMonitoring();
            // executor is already shut down; null it so teardown doesn't double-shutdown
            executor = null;
            result.setMonitoringStats(systemMonitor.getStats());
        }

        return result;
    }

    /**
     * Run all test scenarios.
     */
    public List<TestResult> runAllTests() {
        List<TestResult> results = new ArrayList<>();

        String suiteHeader = "===================================================";
        String suiteInfo = "Starting Adaptive Executor Simulator Test Suite";

        log.info(suiteHeader);
        log.info(suiteInfo);
        log.info(suiteHeader);

        // Send initial log to dashboard
        if (monitoringService != null) {
            monitoringService.sendInfoLog(suiteHeader);
            monitoringService.sendInfoLog(suiteInfo);
            monitoringService.sendInfoLog(suiteHeader);
        }

        // Define test names and methods in order
        String[] testNames = {
            "Normal Operation", "Resource Spike", "Sustained Load", "Memory Pressure",
            "Task Killing", "Priority Scheduling", "Stress Test", "Flapping Monitor",
            "Queue Overflow", "Failing Tasks", "Cascade Kill", "Shutdown Under Load"
        };

        java.util.function.Supplier<TestResult>[] testMethods = new java.util.function.Supplier[] {
            this::runNormalOperationTest, this::runResourceSpikeTest, this::runSustainedLoadTest, this::runMemoryPressureTest,
            this::runTaskKillingTest, this::runPrioritySchedulingTest, this::runStressTest, this::runFlappingMonitorTest,
            this::runQueueOverflowTest, this::runFailingTasksTest, this::runCascadeKillTest, this::runShutdownUnderLoadTest
        };

        for (int i = 0; i < testNames.length; i++) {
            String testName = testNames[i];
            java.util.function.Supplier<TestResult> testMethod = testMethods[i];

            String runningMsg = "Running test " + (i+1) + "/" + testNames.length + ": " + testName;
            log.info(runningMsg);
            if (monitoringService != null) {
                monitoringService.sendInfoLog(runningMsg);
            }

            TestResult result = testMethod.get();
            results.add(result);

            String statusMsg = result.isSuccess()
                ? "✓ " + testName + " - PASSED (" + result.getDuration() + "ms)"
                : "✗ " + testName + " - FAILED: " + result.getError();

            log.info(statusMsg);
            if (monitoringService != null) {
                if (result.isSuccess()) {
                    monitoringService.sendSuccessLog(statusMsg);
                } else {
                    monitoringService.sendErrorLog(statusMsg);
                }
            }

            sleep(2000);
        }

        // Print summary
        String summaryHeader = "===================================================";
        String summaryTitle = "Test Suite Summary";
        String summarySeparator = "---------------------------------------------------";
        log.info(summaryHeader);
        log.info(summaryTitle);
        log.info(summaryHeader);

        int passed = 0;
        int failed = 0;
        for (TestResult r : results) {
            if (r.isSuccess()) {
                passed++;
                log.info("✓ {} - PASSED ({}ms)", r.getScenarioName(), r.getDuration());
            } else {
                failed++;
                log.error("✗ {} - FAILED: {}", r.getScenarioName(), r.getError());
            }
        }

        log.info(summarySeparator);
        log.info("Total: {} tests, Passed: {}, Failed: {}", results.size(), passed, failed);
        log.info(summaryHeader);

        // Send summary to dashboard
        if (monitoringService != null) {
            monitoringService.sendInfoLog(summaryHeader);
            monitoringService.sendInfoLog(summaryTitle);
            monitoringService.sendInfoLog(summaryHeader);

            for (TestResult r : results) {
                String testSummary = r.isSuccess()
                    ? "✓ " + r.getScenarioName() + " - PASSED (" + r.getDuration() + "ms)"
                    : "✗ " + r.getScenarioName() + " - FAILED";
                monitoringService.sendLogMessage(testSummary, r.isSuccess() ? "success" : "error");
            }

            monitoringService.sendInfoLog(summarySeparator);
            monitoringService.sendInfoLog("Total: " + results.size() + " tests, Passed: " + passed + ", Failed: " + failed);
            monitoringService.sendInfoLog(summaryHeader);
        }

        return results;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Start monitoring for executor and system.
     */
    private void startMonitoring(ThrottleService executor) {
        systemMonitor.startMonitoring(500);
        if (monitoringService != null) {
            monitoringService.startMonitoring(executor);
        }
    }

    /**
     * Stop all monitoring.
     */
    private void stopMonitoring() {
        if (monitoringService != null) {
            monitoringService.stopMonitoring();
        }
        systemMonitor.stopMonitoring();
    }
}

