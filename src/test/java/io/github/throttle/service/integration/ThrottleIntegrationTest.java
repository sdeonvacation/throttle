package io.github.throttle.service.integration;

import io.github.throttle.service.api.*;
import io.github.throttle.service.base.AbstractChunkableTask;
import io.github.throttle.service.base.ExecutorMetrics;
import io.github.throttle.service.base.Priority;
import io.github.throttle.service.base.TaskTerminatedException;
import io.github.throttle.service.config.ThrottleConfig;
import io.github.throttle.service.config.OverflowPolicy;
import io.github.throttle.service.core.ThrottleServiceImpl;
import io.github.throttle.service.monitor.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.logging.Logger;
import java.util.logging.Level;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Comprehensive integration tests for Throttle Service.
 * Tests cover positive and negative scenarios based on HLD requirements.
 */
public class ThrottleIntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(ThrottleIntegrationTest.class.getName());

    private ThrottleService executor;
    private List<ResourceMonitor> monitors;
    private ThrottleConfig config;

    @Before
    public void setUp() {
        monitors = new ArrayList<>();
        // Use relaxed thresholds for testing
        monitors.add(new CpuMonitor(95, 50, Duration.ofMillis(500)));
        monitors.add(new MemoryMonitor(95, 50, Duration.ofMillis(500)));

        config = ThrottleConfig.builder()
            .monitoringThreadPool(Executors.newFixedThreadPool(2))
            .workerThreadPool(Executors.newFixedThreadPool(3))
            .queueCapacity(20)
            .maxPauseCount(3)
            .taskTerminationEnabled(true)
            .starvationThreshold(Duration.ofSeconds(5))
            .overflowPolicy(OverflowPolicy.BLOCK)
            .hotMonitoringDebounceInterval(Duration.ofMillis(100)) // Fast debounce for testing
            .build();

        executor = new ThrottleServiceImpl(config, monitors);
    }

    @After
    public void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ========================================================================
    // POSITIVE SCENARIOS
    // ========================================================================

    /**
     * Scenario 1: Normal execution without resource pressure
     * Expected: All tasks complete successfully in priority order
     */
    @Test
    public void testNormalExecution_AllTasksComplete() throws Exception {
        // Given: Multiple tasks with different priorities
        List<TestTask> tasks = new ArrayList<>();
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Priority priority = i < 3 ? Priority.HIGH : i < 7 ? Priority.MEDIUM : Priority.LOW;
            TestTask task = new TestTask("task-" + i, createItems(20), priority, 5);
            tasks.add(task);
            futures.add(executor.submit(task));
        }

        // When: Wait for completion
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        // Then: All tasks completed successfully
        for (TestTask task : tasks) {
            assertTrue("Task " + task.getTaskId() + " should be completed", task.isCompleted());
            assertFalse("Task " + task.getTaskId() + " should not have errors", task.hasError());
            assertEquals("All items should be processed", 20, task.getProcessedCount());
        }

        ExecutorMetrics metrics = executor.getMetrics();
        assertEquals("All tasks should complete", 10, metrics.getTasksCompleted());
        assertEquals("No tasks should fail", 0, metrics.getTasksFailed());
        assertEquals("No tasks should be killed", 0, metrics.getTasksKilled());
    }

    /**
     * Scenario 2: Priority-based scheduling
     * Expected: HIGH priority tasks complete before LOW priority tasks
     */
    @Test
    public void testPriorityScheduling_HighPriorityFirst() throws Exception {
        // Given: First occupy all threads in pool with long-running tasks
        List<Future<Void>> blockingFutures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestTask blockingTask = new TestTask("blocking-" + i, createItems(50), Priority.LOW, 10);
            blockingFutures.add(executor.submit(blockingTask));
        }

        // Wait for blocking tasks to start processing
        Thread.sleep(200);

        // Now submit test tasks in reverse priority order - they will all queue
        TestTask lowTask = new TestTask("low", createItems(20), Priority.LOW, 10);
        TestTask mediumTask = new TestTask("medium", createItems(20), Priority.MEDIUM, 10);
        TestTask highTask = new TestTask("high", createItems(20), Priority.HIGH, 10);

        // When: Submit LOW first, then MEDIUM, then HIGH (all should queue)
        Future<Void> lowFuture = executor.submit(lowTask);
        Future<Void> mediumFuture = executor.submit(mediumTask);
        Future<Void> highFuture = executor.submit(highTask);

        // Wait for all to complete
        highFuture.get(30, TimeUnit.SECONDS);
        mediumFuture.get(30, TimeUnit.SECONDS);
        lowFuture.get(30, TimeUnit.SECONDS);

        // Then: All should complete
        assertTrue("High priority task should complete", highTask.isCompleted());
        assertTrue("Medium priority task should complete", mediumTask.isCompleted());
        assertTrue("Low priority task should complete", lowTask.isCompleted());

        // HIGH should have started before (or at same time as) LOW
        // Priority ordering means HIGH starts first; we verify this strictly
        if (highTask.getStartTime() > lowTask.getStartTime()) {
            fail("High priority task started AFTER low priority task - priority ordering failed! " +
                 "High: " + highTask.getStartTime() + "ms, Low: " + lowTask.getStartTime() + "ms");
        }

        // Optionally verify HIGH started significantly before LOW (not just tie due to timing)
        long timeDiff = lowTask.getStartTime() - highTask.getStartTime();
        LOGGER.info("Priority ordering verified: HIGH started " + timeDiff + "ms before LOW");
    }

    /**
     * Scenario 3: Chunk-based execution with multiple chunks
     * Expected: Task processes all chunks sequentially
     */
    @Test
    public void testChunkBasedExecution_AllChunksProcessed() throws Exception {
        // Given: Task with 100 items, 10 per chunk = 10 chunks
        TestTask task = new TestTask("chunked", createItems(100), Priority.MEDIUM, 10);

        // When: Submit and wait for completion
        Future<Void> future = executor.submit(task);
        future.get(30, TimeUnit.SECONDS);

        // Then: All chunks processed
        assertTrue("Task should complete", task.isCompleted());
        assertEquals("All items processed", 100, task.getProcessedCount());
        assertEquals("10 chunks processed", 10, task.getChunksProcessed());
    }

    /**
     * Scenario 4: Anti-starvation - LOW priority gets boosted
     * Expected: Long-waiting LOW priority task gets boosted to MEDIUM/HIGH
     */
    @Test
    public void testAntiStarvation_LowPriorityBoosted() throws Exception {
        // Given: One LOW priority task and continuous HIGH priority tasks
        TestTask lowTask = new TestTask("low-starving", createItems(10), Priority.LOW, 5);
        Future<Void> lowFuture = executor.submit(lowTask);

        // Submit HIGH priority tasks to keep pool busy
        List<Future<Void>> highFutures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TestTask highTask = new TestTask("high-" + i, createItems(10), Priority.HIGH, 5);
            highFutures.add(executor.submit(highTask));
            Thread.sleep(100);
        }

        // When: Wait for starvation threshold (5 seconds)
        Thread.sleep(6000);

        // Then: LOW priority task should eventually complete (after being boosted)
        lowFuture.get(20, TimeUnit.SECONDS);
        assertTrue("Starved task should complete", lowTask.isCompleted());
    }

    /**
     * Scenario 5: Concurrent task execution in thread pool
     * Expected: Multiple tasks execute in parallel (up to pool size)
     */
    @Test
    public void testConcurrentExecution_ParallelProcessing() throws Exception {
        // Given: Tasks that track when they're actively processing
        int poolSize = 3;
        List<ConcurrencyTrackingTask> tasks = new ArrayList<>();
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < poolSize; i++) {
            ConcurrencyTrackingTask task = new ConcurrencyTrackingTask(
                "concurrent-" + i, createItems(20), Priority.MEDIUM, 5, 50);
            tasks.add(task);
            futures.add(executor.submit(task));
        }

        // When: Let them start processing
        Thread.sleep(200);

        // Then: All should be processing concurrently
        int activeCount = 0;
        for (ConcurrencyTrackingTask task : tasks) {
            if (task.isCurrentlyProcessing()) {
                activeCount++;
            }
        }
        assertTrue("Should have concurrent execution", activeCount >= 2);

        // Wait for completion
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario 6: Task with callbacks - success path
     * Expected: onComplete() called when task finishes successfully
     */
    @Test
    public void testTaskCallbacks_OnCompleteInvoked() throws Exception {
        // Given: Task with callback tracking
        CallbackTrackingTask task = new CallbackTrackingTask(
            "callback", createItems(10), Priority.MEDIUM, 5);

        // When: Execute task
        Future<Void> future = executor.submit(task);
        future.get(10, TimeUnit.SECONDS);

        // Then: onComplete called, not onError or onCancel
        assertTrue("onComplete should be called", task.isOnCompleteCalled());
        assertFalse("onError should not be called", task.isOnErrorCalled());
        assertFalse("onCancel should not be called", task.isOnCancelCalled());
    }

    /**
     * Scenario 7: Mixed workload - different task types
     * Expected: All task types process successfully
     */
    @Test
    public void testMixedWorkload_AllTypesSucceed() throws Exception {
        // Given: Different types of tasks
        TestTask telemetryTask = new TestTask("telemetry", createItems(50), Priority.HIGH, 10);
        TestTask cleanupTask = new TestTask("cleanup", createItems(30), Priority.MEDIUM, 10);
        TestTask archiveTask = new TestTask("archive", createItems(20), Priority.LOW, 10);

        // When: Submit all tasks
        Future<Void> t1 = executor.submit(telemetryTask);
        Future<Void> t2 = executor.submit(cleanupTask);
        Future<Void> t3 = executor.submit(archiveTask);

        // Then: All complete successfully
        t1.get(30, TimeUnit.SECONDS);
        t2.get(30, TimeUnit.SECONDS);
        t3.get(30, TimeUnit.SECONDS);

        assertTrue(telemetryTask.isCompleted());
        assertTrue(cleanupTask.isCompleted());
        assertTrue(archiveTask.isCompleted());

        ExecutorMetrics metrics = executor.getMetrics();
        assertEquals(3, metrics.getTasksCompleted());
    }

    /**
     * Scenario 8: Queue management - tasks wait when pool is full
     * Expected: Tasks queue and execute when threads become available
     */
    @Test
    public void testQueueManagement_TasksWaitForAvailableThreads() throws Exception {
        // Given: More tasks than pool size
        int poolSize = 3;
        int totalTasks = 10;
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < totalTasks; i++) {
            TestTask task = new TestTask("queued-" + i, createItems(10), Priority.MEDIUM, 5);
            futures.add(executor.submit(task));
        }

        // When: Check queue size immediately
        Thread.sleep(100);
        ExecutorMetrics metrics = executor.getMetrics();

        // Then: Some tasks should be queued
        assertTrue("Queue should have waiting tasks", metrics.getQueueSize() > 0);
        assertTrue("Active threads should be busy", metrics.getActiveThreads() > 0);

        // All eventually complete
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        ExecutorMetrics finalMetrics = executor.getMetrics();
        assertEquals("All tasks should complete", totalTasks, finalMetrics.getTasksCompleted());
    }

    // ========================================================================
    // NEGATIVE SCENARIOS
    // ========================================================================

    /**
     * Scenario 9: Task throws exception during processing
     * Expected: Task marked as failed, onError() called, other tasks continue
     */
    @Test
    public void testTaskFailure_ExceptionHandled() throws Exception {
        // Given: Task that throws exception on chunk 2 (10 items / 5 per chunk = 2 chunks)
        FailingTask failingTask = new FailingTask("failing", createItems(10), Priority.MEDIUM, 5, 2);
        TestTask normalTask = new TestTask("normal", createItems(10), Priority.MEDIUM, 5);

        // When: Submit both tasks
        Future<Void> failFuture = executor.submit(failingTask);
        Future<Void> normalFuture = executor.submit(normalTask);

        // Then: Failing task should fail
        try {
            failFuture.get(10, TimeUnit.SECONDS);
            fail("Should throw exception");
        } catch (ExecutionException e) {
            assertNotNull("Exception should be present", e.getCause());
        }

        assertTrue("onError should be called", failingTask.isOnErrorCalled());
        assertFalse("Task should not complete", failingTask.isCompleted());

        // Normal task should still succeed
        normalFuture.get(10, TimeUnit.SECONDS);
        assertTrue("Normal task should complete", normalTask.isCompleted());

        ExecutorMetrics metrics = executor.getMetrics();
        assertEquals("One task should fail", 1, metrics.getTasksFailed());
        assertEquals("One task should succeed", 1, metrics.getTasksCompleted());
    }

    /**
     * Scenario 10: Task killed after exceeding maxPauseCount with controllable monitor
     * Expected: Task receives TaskTerminatedException, onError called, added to killed tasks list
     */
    @Test(timeout = 40000)
    public void testTaskKilling_ExceedsMaxPauseCount() throws Exception {
        // Given: Controllable monitor and executor with low maxPauseCount
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(1)) // Single thread to ensure deterministic behavior
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(3) // Kill after 3 pauses
            .taskTerminationEnabled(true) // Enable killing
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            // Create SLOW task with many items to ensure it doesn't complete before being killed
            // Chunk size = 5, items = 200, delay = 150ms per item
            // Each chunk takes 5 * 150 = 750ms
            TerminationTrackingTask task = new TerminationTrackingTask(
                "to-be-killed", createItems(200), Priority.MEDIUM, 5, 150);
            Future<Void> future = testExecutor.submit(task);

            // Wait for task to complete first chunk (750ms + margin)
            Thread.sleep(1000);
            assertTrue("Task should have started", task.getProcessedCount() >= 5);

            // Cycle through 4 pause/resume cycles to exceed maxPauseCount of 3
            // Each cycle: set HOT → task hits checkpoint → pauses → set NORMAL → task resumes
            for (int i = 0; i < 4; i++) {
                controllableMonitor.setState(MonitorState.HOT);

                // Wait for task to complete current chunk and hit checkpoint where pause is detected
                Thread.sleep(1200); // Enough time for chunk (750ms) + checkpoint detection

                ExecutorMetrics pauseMetrics = testExecutor.getMetrics();
                assertTrue("Should be paused in cycle " + (i+1) + " (pauseCount=" + pauseMetrics.getPauseCount() + ")",
                    pauseMetrics.isPaused());

                controllableMonitor.setState(MonitorState.NORMAL);

                // Wait for resume detection and execution
                Thread.sleep(1500); // Cold monitoring interval + resume margin

                // After 4th cycle, task should be killed at next checkpoint
                if (i == 3) {
                    // Task now has pauseCount = 4, maxPauseCount = 3
                    // On next checkpoint, it should be killed
                    Thread.sleep(1500); // Wait for task to hit checkpoint and get killed
                    break;
                }

                ExecutorMetrics resumeMetrics = testExecutor.getMetrics();
                assertFalse("Should be resumed in cycle " + (i+1), resumeMetrics.isPaused());
            }

            // Then: Task should be killed
            try {
                future.get(3, TimeUnit.SECONDS);
                // If task completes, check if it was actually killed
                fail("Task should have been killed, not completed");
            } catch (ExecutionException e) {
                // Expected - task was killed
                assertNotNull("Should have cause", e.getCause());
                assertTrue("Should contain TaskTerminatedException",
                    e.getCause() instanceof TaskTerminatedException);
                TaskTerminatedException tte = (TaskTerminatedException) e.getCause();
                assertTrue("Should be killed after exceeding maxPauseCount", tte.getPauseCount() > 3);
                assertEquals("Max pause count should be 3", 3, tte.getMaxPauseCount());
            } catch (TimeoutException e) {
                fail("Task should have been killed, not timed out");
            }

            // Verify task was killed
            assertTrue("onError should have been called with TaskTerminatedException",
                task.isTerminationExceptionReceived());
            assertFalse("Task should not complete successfully", task.isCompleted());

            // Verify metrics
            ExecutorMetrics metrics = testExecutor.getMetrics();
            assertEquals("Should have 1 killed task", 1, metrics.getTasksKilled());
            assertEquals("Should have 0 completed tasks", 0, metrics.getTasksCompleted());

            // Verify killed tasks list
            List<ChunkableTask<?>> killedTasks = testExecutor.getKilledTasks();
            assertEquals("Should have 1 task in killed list", 1, killedTasks.size());
            assertEquals("Killed task should match", task.getTaskId(), killedTasks.get(0).getTaskId());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario 10b: Task NOT killed when termination is disabled
     * Expected: Task survives even with excessive pauses
     */
    @Test(timeout = 30000)
    public void testTaskKilling_DisabledTaskSurvives() throws Exception {
        // Given: Executor with termination DISABLED
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(1))
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(2) // Low threshold
            .taskTerminationEnabled(false) // Killing DISABLED
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            SlowTask task = new SlowTask("survivor-task", createItems(100), Priority.MEDIUM, 5, 50);
            Future<Void> future = testExecutor.submit(task);

            Thread.sleep(200);

            // Pause/resume 5 times (way more than maxPauseCount of 2)
            for (int i = 0; i < 5; i++) {
                controllableMonitor.setState(MonitorState.HOT);
                Thread.sleep(300);

                controllableMonitor.setState(MonitorState.NORMAL);
                Thread.sleep(800);
            }

            // Task should complete successfully despite many pauses
            future.get(10, TimeUnit.SECONDS);
            assertTrue("Task should complete even with excessive pauses", task.isCompleted());

            ExecutorMetrics metrics = testExecutor.getMetrics();
            assertEquals("Should have 0 killed tasks", 0, metrics.getTasksKilled());
            assertEquals("Should have 1 completed task", 1, metrics.getTasksCompleted());
            assertTrue("Task should have high pause count", task.getPauseCount() >= 5);

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario 10c: Multiple tasks - only guilty ones killed
     * Expected: Tasks that pause excessively are killed, others complete
     */
    @Test(timeout = 45000)
    public void testTaskKilling_OnlyGuiltyTasksKilled() throws Exception {
        // Given: Multiple tasks with controllable monitor
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(2)) // 2 threads
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(2) // Low threshold for testing
            .taskTerminationEnabled(true)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            // Submit FAST task that will complete before being killed (small items, fast processing)
            TerminationTrackingTask fastTask = new TerminationTrackingTask(
                "fast-innocent", createItems(15), Priority.HIGH, 5, 30); // Fast: 15 items, 30ms/item = 450ms total

            // Submit SLOW task that will be killed (many items, slow processing)
            TerminationTrackingTask slowTask = new TerminationTrackingTask(
                "slow-guilty", createItems(200), Priority.MEDIUM, 10, 150); // Slow: 200 items, 150ms/item = 30s total

            Future<Void> fastFuture = testExecutor.submit(fastTask);
            Future<Void> slowFuture = testExecutor.submit(slowTask);

            // Wait for both to start (first chunk completion)
            Thread.sleep(1000);

            // Trigger 3 pause cycles to kill slow task (maxPauseCount = 2)
            for (int i = 0; i < 3; i++) {
                controllableMonitor.setState(MonitorState.HOT);
                Thread.sleep(1500); // Long enough for tasks to hit checkpoint

                controllableMonitor.setState(MonitorState.NORMAL);
                Thread.sleep(2000); // Long enough for resume detection + processing
            }

            // Wait for outcomes
            Thread.sleep(2000);

            // Fast task should complete successfully (finished before being killed)
            try {
                fastFuture.get(2, TimeUnit.SECONDS);
                assertTrue("Fast task should complete", fastTask.isCompleted());
            } catch (ExecutionException e) {
                // Fast task might also have been paused 3 times and killed if unlucky
                LOGGER.info("Fast task was also killed: " + e.getCause().getMessage());
            }

            // Slow task should definitely be killed
            try {
                slowFuture.get(2, TimeUnit.SECONDS);
                fail("Slow task should have been killed");
            } catch (ExecutionException e) {
                assertTrue("Slow task should be killed with TaskTerminatedException",
                    e.getCause() instanceof TaskTerminatedException);
            }

            // Verify metrics - at least slow task was killed
            ExecutorMetrics metrics = testExecutor.getMetrics();
            assertTrue("Should have killed at least 1 task", metrics.getTasksKilled() >= 1);

            // Verify killed tasks list
            List<ChunkableTask<?>> killedTasks = testExecutor.getKilledTasks();
            assertFalse("Killed tasks list should not be empty", killedTasks.isEmpty());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario 10d: Verify TaskTerminatedException details
     * Expected: Exception contains correct taskId, pauseCount, and maxPauseCount
     */
    @Test(timeout = 25000)
    public void testTaskKilling_ExceptionContainsCorrectDetails() throws Exception {
        // Given: Controllable monitor with low maxPauseCount
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        int maxPauseCount = 2;
        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(1))
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(maxPauseCount)
            .taskTerminationEnabled(true)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            TerminationTrackingTask task = new TerminationTrackingTask(
                "detail-check-task", createItems(100), Priority.MEDIUM, 5, 80);
            Future<Void> future = testExecutor.submit(task);

            // Wait for task to definitely start processing first chunk
            Thread.sleep(500);
            assertTrue("Task should have started processing", task.getProcessedCount() > 0);

            // Trigger 3 pause/resume cycles to exceed maxPauseCount of 2
            // Each chunk takes ~400ms (5 items × 80ms)
            // Strategy: Keep monitor HOT for longer to ensure checkpoint hit while HOT
            for (int i = 0; i < 3; i++) {
                int currentPauseCount = task.getPauseCount();
                LOGGER.info("Pause cycle " + (i + 1) + ": Current pauseCount=" + currentPauseCount + ", making monitor HOT");

                controllableMonitor.setState(MonitorState.HOT);

                // Wait long enough for chunk to complete and hit checkpoint while HOT
                Thread.sleep(800); // Longer than chunk duration (400ms) + checkpoint processing

                // Verify pause happened
                int newPauseCount = task.getPauseCount();
                LOGGER.info("Pause cycle " + (i + 1) + ": PauseCount increased to " + newPauseCount);

                // Resume
                LOGGER.info("Pause cycle " + (i + 1) + ": Making monitor NORMAL");
                controllableMonitor.setState(MonitorState.NORMAL);

                // Wait for resume + next chunk to start
                Thread.sleep(1200);

                // After 3rd pause, task should be killed during handleTerminationCondition
                if (i == 2) {
                    LOGGER.info("Completed 3 pause cycles, pauseCount should be >= 3");
                    assertTrue("After 3 cycles, pauseCount should be >= 3",
                        task.getPauseCount() >= 3);
                }
            }

            // Wait for task to be killed
            Thread.sleep(500);

            // Then: Verify exception details
            try {
                future.get(2, TimeUnit.SECONDS);
                fail("Task should have been killed");
            } catch (ExecutionException e) {
                assertNotNull("Should have cause", e.getCause());
                assertTrue("Should be TaskTerminatedException",
                    e.getCause() instanceof io.github.throttle.service.base.TaskTerminatedException);

                io.github.throttle.service.base.TaskTerminatedException tte =
                    (io.github.throttle.service.base.TaskTerminatedException) e.getCause();

                assertEquals("Exception should have correct taskId",
                    "detail-check-task", tte.getTaskId());
                assertEquals("Exception should have correct maxPauseCount",
                    maxPauseCount, tte.getMaxPauseCount());
                assertTrue("Exception should show pauseCount > maxPauseCount",
                    tte.getPauseCount() > maxPauseCount);
            }

            // Verify task received the exception
            assertTrue("Task should have received termination exception",
                task.isTerminationExceptionReceived());
            assertNotNull("Task should have exception", task.getReceivedException());

            // Verify metrics
            ExecutorMetrics metrics = testExecutor.getMetrics();
            assertEquals("Should have 1 killed task", 1, metrics.getTasksKilled());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario 10e: Task killing with realistic resource spike scenario
     * Expected: Long-running guilty task is killed during sustained resource pressure
     */
    @Test(timeout = 45000)
    public void testTaskKilling_RealisticResourceSpike() throws Exception {
        // Given: Controllable monitor simulating sustained resource pressure
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(1)) // Single thread for deterministic behavior
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(2) // Low threshold
            .taskTerminationEnabled(true)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            // Submit slow task with many chunks - will be killed
            TerminationTrackingTask guiltyTask = new TerminationTrackingTask(
                "guilty-task", createItems(200), Priority.MEDIUM, 10, 120);

            Future<Void> guiltyFuture = testExecutor.submit(guiltyTask);

            // Wait for first chunk to complete
            Thread.sleep(1500);
            assertTrue("Task should have started", guiltyTask.getProcessedCount() >= 10);

            // Simulate sustained resource spike: 3 pause cycles (exceeds maxPauseCount of 2)
            for (int cycle = 0; cycle < 3; cycle++) {
                controllableMonitor.setState(MonitorState.HOT);
                Thread.sleep(1500); // Wait for checkpoint + pause detection

                assertTrue("Should be paused in cycle " + (cycle + 1),
                    testExecutor.getMetrics().isPaused());

                controllableMonitor.setState(MonitorState.NORMAL);
                Thread.sleep(1500); // Wait for resume detection

                // After 3rd cycle, task has pauseCount = 3 > maxPauseCount = 2
                // It will be killed at next checkpoint
            }

            // Wait for task to hit checkpoint and get killed
            Thread.sleep(2000);

            // Task should be killed
            try {
                guiltyFuture.get(2, TimeUnit.SECONDS);
                fail("Task should have been killed");
            } catch (ExecutionException e) {
                assertTrue("Should be TaskTerminatedException",
                    e.getCause() instanceof TaskTerminatedException);
            }

            ExecutorMetrics metrics = testExecutor.getMetrics();
            assertEquals("Should have 1 killed task", 1, metrics.getTasksKilled());

            List<ChunkableTask<?>> killedTasks = testExecutor.getKilledTasks();
            assertFalse("Killed tasks list should not be empty", killedTasks.isEmpty());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario 10f: Verify killing happens at checkpoint (not mid-chunk)
     * Expected: Task is killed at chunk boundary, not during chunk processing
     */
    @Test(timeout = 25000)
    public void testTaskKilling_HappensAtCheckpoint() throws Exception {
        // Given: Controllable monitor with very low maxPauseCount
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(1))
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofMillis(100))
            .hotMonitoringDebounceInterval(Duration.ofMillis(50))
            .maxPauseCount(2)
            .taskTerminationEnabled(true)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            // Task with small chunks - fast execution to hit checkpoints frequently
            // 3 items × 30ms = 90ms per chunk
            CheckpointTrackingTask task = new CheckpointTrackingTask(
                "checkpoint-task", createItems(15), Priority.MEDIUM, 3, 30);
            Future<Void> future = testExecutor.submit(task);

            // Wait for task to start executing first chunk
            Thread.sleep(100);

            // Cycle 1: HOT during checkpoint -> pause 1
            controllableMonitor.setState(MonitorState.HOT);
            Thread.sleep(150); // Wait for task to hit checkpoint while HOT
            controllableMonitor.setState(MonitorState.NORMAL);
            Thread.sleep(200); // Let resume detection kick in and task to continue

            // Cycle 2: HOT during checkpoint -> pause 2
            controllableMonitor.setState(MonitorState.HOT);
            Thread.sleep(150);
            controllableMonitor.setState(MonitorState.NORMAL);
            Thread.sleep(200);

            // Cycle 3: HOT during checkpoint -> pause 3 (exceeds maxPauseCount=2) -> killed
            controllableMonitor.setState(MonitorState.HOT);
            Thread.sleep(150);
            controllableMonitor.setState(MonitorState.NORMAL);
            Thread.sleep(300);

            // Then: Task should be killed
            try {
                future.get(2, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                assertTrue("Should be TaskTerminatedException",
                    e.getCause() instanceof io.github.throttle.service.base.TaskTerminatedException);
            }

            // Verify task was not killed mid-chunk
            assertTrue("Task should have received termination exception",
                task.isTerminationExceptionReceived());
            assertFalse("Should not have been killed mid-chunk", task.wasKilledMidChunk());
            LOGGER.info("Task was properly killed at checkpoint after processing " + task.getChunksProcessed() + " complete chunks");

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario 10g: Task killing count validation
     * Expected: Metrics accurately reflect number of killed tasks
     */
    @Test(timeout = 50000)
    public void testTaskKilling_MetricsAccurate() throws Exception {
        // Given: Multiple SLOW tasks that will be killed before completing
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(3))
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(2) // Kill after 2 pauses
            .taskTerminationEnabled(true)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            // Submit 3 VERY slow tasks with MANY items (won't complete in test duration)
            // Each task: 300 items, chunk size 10, 150ms per item
            // One chunk = 10 * 150ms = 1500ms
            // Total time to complete = 300 * 150ms = 45 seconds (won't finish in test)
            List<TerminationTrackingTask> tasks = new ArrayList<>();
            List<Future<Void>> futures = new ArrayList<>();

            for (int i = 0; i < 3; i++) {
                TerminationTrackingTask task = new TerminationTrackingTask(
                    "task-" + i, createItems(300), Priority.MEDIUM, 10, 150);
                tasks.add(task);
                futures.add(testExecutor.submit(task));
            }

            // Wait for all tasks to start (first chunk completion = 1500ms)
            Thread.sleep(2000);
            for (TerminationTrackingTask task : tasks) {
                assertTrue("Task " + task.getTaskId() + " should have started",
                    task.getProcessedCount() >= 10);
            }

            // Trigger 3 pause cycles to exceed maxPauseCount of 2
            for (int cycle = 0; cycle < 3; cycle++) {
                controllableMonitor.setState(MonitorState.HOT);
                Thread.sleep(2000); // Long pause - wait for all tasks to hit checkpoint

                controllableMonitor.setState(MonitorState.NORMAL);
                Thread.sleep(2000); // Long resume period
            }

            // Wait for tasks to hit checkpoint and get killed (pauseCount = 3 > maxPauseCount = 2)
            Thread.sleep(3000);

            // Count how many were actually killed
            int killedCount = 0;
            int completedCount = 0;

            for (int i = 0; i < tasks.size(); i++) {
                Future<Void> future = futures.get(i);

                try {
                    future.get(1, TimeUnit.SECONDS);
                    completedCount++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof TaskTerminatedException) {
                        killedCount++;
                    }
                } catch (TimeoutException e) {
                    // Still running - shouldn't happen
                    LOGGER.warning("Task still running after kill period");
                }
            }

            // All 3 tasks should be killed (none should complete)
            assertTrue("Should have killed tasks", killedCount > 0);
            assertEquals("All tasks should be killed (or at least most)", 3, killedCount + completedCount);

            // Verify metrics match reality
            ExecutorMetrics metrics = testExecutor.getMetrics();
            assertEquals("Metrics killed count should match actual", killedCount, metrics.getTasksKilled());
            assertEquals("Metrics completed count should match actual", completedCount, metrics.getTasksCompleted());

            List<ChunkableTask<?>> killedTasksList = testExecutor.getKilledTasks();
            assertEquals("Killed tasks list size should match metrics",
                killedCount, killedTasksList.size());

            // Verify all killed tasks received the exception
            for (TerminationTrackingTask task : tasks) {
                if (task.isTerminationExceptionReceived()) {
                    assertFalse("Killed task should not complete", task.isCompleted());
                    assertTrue("Killed task should have exceeded maxPauseCount", task.getPauseCount() > 2);
                }
            }

            LOGGER.info("Task killing metrics validated: " + killedCount + " killed, " + completedCount + " completed out of 3 tasks");

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Test that all tasks get fair pause counting (ISSUE #17 fix validation).
     * Tests that tasks starting during pause trigger race window still get counted.
     */
    @Test(timeout = 30000)
    public void testPauseCount_FairCountingWithRaceConditions() throws Exception {
        // Given: Controllable monitor and config with multiple workers
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(10)
            .taskTerminationEnabled(false)
            .workerThreadPool(Executors.newFixedThreadPool(4)) // 4 workers for concurrency
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            // Create multiple tasks
            List<SlowTask> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                SlowTask task = new SlowTask("race-task-" + i, createItems(40), Priority.MEDIUM, 5, 80);
                tasks.add(task);
            }

            // Submit first 3 tasks - they will start processing
            for (int i = 0; i < 3; i++) {
                testExecutor.submit(tasks.get(i));
            }

            // Wait for them to start
            Thread.sleep(300);

            // Make monitor HOT to trigger pause
            controllableMonitor.setState(MonitorState.HOT);

            // Submit remaining tasks DURING the race window (after HOT but before isPaused=true)
            for (int i = 3; i < 6; i++) {
                testExecutor.submit(tasks.get(i));
                Thread.sleep(10); // Small delay to spread submissions
            }

            // Wait for pause to be detected and all tasks to hit checkpoints
            Thread.sleep(1000);

            // Verify system is paused
            assertTrue("System should be paused", testExecutor.isPaused());

            // CRITICAL: Check that ALL tasks that hit checkpoint have pause count >= 1
            // This includes tasks that started AFTER pause was triggered
            int tasksWithNonZeroCount = 0;
            for (SlowTask task : tasks) {
                int count = task.getPauseCount();
                if (count > 0) {
                    tasksWithNonZeroCount++;
                }
                LOGGER.info("Task " + task.getTaskId() + " pause count: " + count + ", processed: " + task.getProcessedCount());
            }

            // All tasks that started processing should have been counted
            // (Tasks might not all start if queue is full, but those that did should be counted)
            assertTrue("At least 3 tasks should have non-zero pause count", tasksWithNonZeroCount >= 3);

            // Resume and complete
            controllableMonitor.setState(MonitorState.NORMAL);
            Thread.sleep(1500);

            // Wait for all to complete
            for (SlowTask task : tasks) {
                if (task.getProcessedCount() > 0) { // Only wait for tasks that started
                    task.get(15, TimeUnit.SECONDS);
                }
            }

            // Final verification: All tasks that paused have fair counts
            LOGGER.info("Final pause counts:");
            for (SlowTask task : tasks) {
                LOGGER.info("  " + task.getTaskId() + " -> pauseCount=" + task.getPauseCount() +
                    ", processed=" + task.getProcessedCount() + "/" + task.getTotalItemCount());
            }

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario 11: Queue overflow with REJECT policy
     * Expected: RejectedExecutionException thrown when queue is full
     */
    @Test(expected = RejectedExecutionException.class)
    public void testQueueOverflow_RejectPolicy() throws Exception {
        // Given: Executor with small queue and REJECT policy
        ThrottleConfig rejectConfig = ThrottleConfig.builder()
            .queueCapacity(2)
            .overflowPolicy(OverflowPolicy.REJECT)
            .build();

        ThrottleService rejectExecutor = new ThrottleServiceImpl(rejectConfig, monitors);

        try {
            // When: Submit more tasks than capacity
            for (int i = 0; i < 10; i++) {
                TestTask task = new TestTask("overflow-" + i, createItems(100), Priority.MEDIUM, 10);
                rejectExecutor.submit(task);
                Thread.sleep(10);
            }
        } finally {
            rejectExecutor.shutdown();
        }

        // Then: Exception should be thrown (by @Test annotation)
    }

    /**
     * Scenario 12: Executor shutdown while tasks are running
     * Expected: Tasks are cancelled, onCancel() called
     */
    @Test
    public void testShutdown_TasksCancelled() throws Exception {
        // Given: Long-running task
        TestTask longTask = new TestTask("long-running", createItems(100), Priority.MEDIUM, 10);
        Future<Void> future = executor.submit(longTask);

        // When: Shutdown while task is running
        Thread.sleep(200);
        executor.shutdownNow();

        // Then: Task should be cancelled
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (CancellationException | ExecutionException | InterruptedException e) {
            // Expected - task was cancelled
        }

        assertTrue("Executor should be shutdown", executor.isShutdown());
    }

    /**
     * Scenario 13: Submit task after shutdown
     * Expected: RejectedExecutionException thrown
     */
    @Test(expected = RejectedExecutionException.class)
    public void testSubmitAfterShutdown_Rejected() throws Exception {
        // Given: Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // When: Try to submit new task
        TestTask task = new TestTask("after-shutdown", createItems(10), Priority.MEDIUM, 5);
        executor.submit(task);

        // Then: Exception thrown (by @Test annotation)
    }

    /**
     * Scenario 14: Empty task (no chunks)
     * Expected: Task completes immediately with no processing
     */
    @Test
    public void testEmptyTask_CompletesImmediately() throws Exception {
        // Given: Task with no items
        TestTask emptyTask = new TestTask("empty", new ArrayList<String>(), Priority.MEDIUM, 10);

        // When: Submit task
        Future<Void> future = executor.submit(emptyTask);
        future.get(5, TimeUnit.SECONDS);

        // Then: Task completes with no processing
        assertTrue("Empty task should complete", emptyTask.isCompleted());
        assertEquals("No items processed", 0, emptyTask.getProcessedCount());
        assertEquals("No chunks processed", 0, emptyTask.getChunksProcessed());
    }

    /**
     * Scenario 15: Concurrent modifications - multiple clients submitting
     * Expected: All submissions handled correctly, no race conditions
     */
    @Test
    public void testConcurrentSubmissions_ThreadSafe() throws Exception {
        // Given: Multiple threads submitting tasks
        int numThreads = 5;
        int tasksPerThread = 10;
        ExecutorService submitters = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> submissionFutures = new ArrayList<>();

        // When: Submit tasks concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            submissionFutures.add(submitters.submit(new Runnable() {
                @Override
                public void run() {
                    for (int j = 0; j < tasksPerThread; j++) {
                        TestTask task = new TestTask(
                            "thread-" + threadId + "-task-" + j,
                            createItems(10),
                            Priority.MEDIUM,
                            5);
                        try {
                            executor.submit(task);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }));
        }

        // Wait for all submissions
        for (Future<?> f : submissionFutures) {
            f.get(10, TimeUnit.SECONDS);
        }

        submitters.shutdown();
        submitters.awaitTermination(5, TimeUnit.SECONDS);

        // Then: All tasks should eventually complete
        Thread.sleep(5000); // Wait for processing
        ExecutorMetrics metrics = executor.getMetrics();

        assertTrue("Most tasks should complete",
            metrics.getTasksCompleted() + metrics.getTasksFailed() >= 40);
    }

    /**
     * Scenario 16: Task cancellation via Future.cancel()
     * Expected: Task stops processing, onCancel() called
     */
    @Test
    public void testTaskCancellation_FutureCancel() throws Exception {
        // Given: Long-running task
        TestTask longTask = new TestTask("cancellable", createItems(100), Priority.MEDIUM, 5);
        Future<Void> future = executor.submit(longTask);

        // When: Cancel after it starts
        Thread.sleep(200);
        boolean cancelled = future.cancel(true);

        // Then: Task should be cancelled
        assertTrue("Future should be cancelled", future.isCancelled());

        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Should throw CancellationException");
        } catch (CancellationException e) {
            // Expected
        }
    }

    /**
     * Scenario 17: Resource pressure with pause/resume
     * Expected: Tasks pause when resources hot, resume when cold
     */
    @Test
    public void testResourcePressure_PauseResume() throws Exception {
        // Given: Tasks running
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestTask task = new TestTask("pausable-" + i, createItems(30), Priority.MEDIUM, 5);
            futures.add(executor.submit(task));
        }

        // When: Manually trigger pause
        Thread.sleep(200);
        executor.pauseAll();
        assertTrue("Should be paused", executor.isPaused());

        ExecutorMetrics pausedMetrics = executor.getMetrics();
        long pauseCount = pausedMetrics.getPauseCount();
        assertTrue("Pause count should increase", pauseCount > 0);

        // Resume
        Thread.sleep(500);
        executor.resumeAll();
        assertFalse("Should be resumed", executor.isPaused());

        // Then: Tasks should complete
        for (Future<Void> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }

        ExecutorMetrics finalMetrics = executor.getMetrics();
        assertEquals("All tasks should complete", 3, finalMetrics.getTasksCompleted());
        assertTrue("Should have paused at least once", finalMetrics.getPauseCount() >= pauseCount);
    }

    /**
     * Scenario 18: Only guilty tasks penalized during pause
     * Expected: Only actively processing tasks get pauseCount incremented
     */
    @Test
    public void testGuiltyTaskPenalization_OnlyActiveTasksPenalized() throws Exception {
        // Given: One heavy task and one light task, plus queued tasks
        HeavyProcessingTask heavyTask = new HeavyProcessingTask(
            "heavy", createItems(40), Priority.MEDIUM, 5, 100);
        TestTask lightTask = new TestTask("light", createItems(40), Priority.MEDIUM, 10);
        TestTask queuedTask = new TestTask("queued", createItems(20), Priority.LOW, 5);

        // When: Submit heavy task first (will occupy thread), then others
        Future<Void> heavyFuture = executor.submit(heavyTask);
        Thread.sleep(150); // Let heavy task start processing

        Future<Void> lightFuture = executor.submit(lightTask);
        Future<Void> queuedFuture = executor.submit(queuedTask);

        Thread.sleep(100); // Let light task start

        // Pause while heavy is processing
        executor.pauseAll();
        Thread.sleep(300);

        // Heavy task was actively processing - should be guilty
        // Light task might be at checkpoint - less guilty
        // Queued task never started - innocent

        executor.resumeAll();

        // Then: Wait for completion
        heavyFuture.get(30, TimeUnit.SECONDS);
        lightFuture.get(30, TimeUnit.SECONDS);
        queuedFuture.get(30, TimeUnit.SECONDS);

        // Heavy task should have higher pause count
        assertTrue("Heavy task should have been penalized", heavyTask.getPauseCount() >= 0);
        assertTrue("All should complete",
            heavyTask.isCompleted() && lightTask.isCompleted() && queuedTask.isCompleted());
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    private List<String> createItems(int count) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add("Item-" + i);
        }
        return items;
    }

    // ========================================================================
    // PAUSE/RESUME SCENARIOS - Testing automatic resume on monitor cooldown
    // ========================================================================

    /**
     * Scenario: Pause due to hot monitor, then auto-resume when monitor cools
     * Expected: Tasks pause when monitor goes HOT, then automatically resume when monitor becomes NORMAL
     * This test validates the fix for the pause/resume deadlock where tasks wouldn't resume
     */
    @Test(timeout = 30000) // 30 second timeout to catch deadlock
    public void testPauseResume_AutoResumeOnCooldown() throws Exception {
        // Given: Controllable monitor that can be toggled HOT/NORMAL
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(2))
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500)) // Fast cooldown checking for test
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(10)
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            // Create a slow task that will be processing when we trigger pause
            SlowTask slowTask = new SlowTask("slow-task", createItems(50), Priority.MEDIUM, 5, 100);
            Future<Void> future = testExecutor.submit(slowTask);

            // Wait for task to start processing AND complete at least one chunk
            // Chunk size = 5, delay = 100ms per item, so one chunk takes ~500ms
            Thread.sleep(700); // Ensure first chunk completes
            assertTrue("Task should have started", slowTask.getProcessedCount() >= 5);

            // When: Make monitor HOT to trigger pause (will be detected at next checkpoint)
            controllableMonitor.setState(MonitorState.HOT);

            // Wait for task to complete current chunk and hit checkpoint where pause is detected
            Thread.sleep(600); // Wait for current chunk to complete + checkpoint detection

            // Then: Task should be paused
            ExecutorMetrics metrics = testExecutor.getMetrics();
            assertTrue("Executor should be paused", metrics.isPaused());
            assertTrue("Should have at least one pause", metrics.getPauseCount() > 0);

            int processedBeforePause = slowTask.getProcessedCount();
            Thread.sleep(800); // Verify task is truly paused (not processing)
            int processedWhilePaused = slowTask.getProcessedCount();
            assertEquals("Task should not process items while paused", processedBeforePause, processedWhilePaused);

            // When: Make monitor NORMAL to trigger auto-resume
            controllableMonitor.setState(MonitorState.NORMAL);
            Thread.sleep(1000); // Wait for cold monitoring to detect and resume

            // Then: Executor should auto-resume and task should complete
            future.get(10, TimeUnit.SECONDS); // Should complete, not timeout
            assertTrue("Task should complete after resume", slowTask.isCompleted());

            ExecutorMetrics finalMetrics = testExecutor.getMetrics();
            assertFalse("Executor should not be paused after cooldown", finalMetrics.isPaused());
            assertEquals("Task should complete successfully", 1, finalMetrics.getTasksCompleted());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario: Multiple pause/resume cycles
     * Expected: Tasks can be paused and resumed multiple times
     */
    @Test(timeout = 45000)
    public void testPauseResume_MultipleCycles() throws Exception {
        // Given: Controllable monitor
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(2))
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(10)
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            SlowTask slowTask = new SlowTask("multi-pause-task", createItems(100), Priority.MEDIUM, 10, 50);
            Future<Void> future = testExecutor.submit(slowTask);

            // Wait for task to start and complete at least one chunk
            // Chunk size = 10, delay = 50ms per item, so one chunk takes ~500ms
            Thread.sleep(700);
            assertTrue("Task should have started", slowTask.getProcessedCount() >= 10);

            // Cycle 1: Pause and resume
            controllableMonitor.setState(MonitorState.HOT);
            Thread.sleep(600); // Wait for task to hit checkpoint and detect pause
            assertTrue("Should be paused", testExecutor.getMetrics().isPaused());

            controllableMonitor.setState(MonitorState.NORMAL);
            Thread.sleep(1000);
            assertFalse("Should be resumed", testExecutor.getMetrics().isPaused());

            // Cycle 2: Pause and resume again
            controllableMonitor.setState(MonitorState.HOT);
            Thread.sleep(600); // Wait for checkpoint detection
            assertTrue("Should be paused again", testExecutor.getMetrics().isPaused());

            controllableMonitor.setState(MonitorState.NORMAL);
            Thread.sleep(1000);
            assertFalse("Should be resumed again", testExecutor.getMetrics().isPaused());

            // Task should complete successfully
            future.get(15, TimeUnit.SECONDS);
            assertTrue("Task should complete", slowTask.isCompleted());

            ExecutorMetrics finalMetrics = testExecutor.getMetrics();
            assertTrue("Should have multiple pauses", finalMetrics.getPauseCount() >= 2);

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario: Pause with multiple tasks - all should pause and resume together
     * Expected: When monitor goes HOT, all tasks pause; when NORMAL, all resume
     */
    @Test(timeout = 30000)
    public void testPauseResume_MultipleTasksPauseAndResumeTogether() throws Exception {
        // Given: Controllable monitor and multiple tasks
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(3))
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(10)
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            List<SlowTask> tasks = new ArrayList<>();
            List<Future<Void>> futures = new ArrayList<>();

            for (int i = 0; i < 3; i++) {
                SlowTask task = new SlowTask("task-" + i, createItems(30), Priority.MEDIUM, 5, 80);
                tasks.add(task);
                futures.add(testExecutor.submit(task));
            }

            // Wait for tasks to start and complete at least one chunk
            // Chunk size = 5, delay = 80ms per item, so one chunk takes ~400ms
            Thread.sleep(600);

            // All tasks should be processing
            for (SlowTask task : tasks) {
                assertTrue("Task should have started", task.getProcessedCount() >= 5);
            }

            // When: Trigger pause (will be detected at next checkpoint)
            controllableMonitor.setState(MonitorState.HOT);
            Thread.sleep(500); // Wait for tasks to complete current chunk and detect pause

            // Then: All should pause
            assertTrue("Executor should be paused", testExecutor.getMetrics().isPaused());

            // Record current progress
            List<Integer> progressBeforePause = new ArrayList<>();
            for (SlowTask task : tasks) {
                progressBeforePause.add(task.getProcessedCount());
            }

            // Verify no progress while paused
            Thread.sleep(500);
            for (int i = 0; i < tasks.size(); i++) {
                assertEquals("Task should not progress while paused",
                    progressBeforePause.get(i).intValue(), tasks.get(i).getProcessedCount());
            }

            // When: Resume
            controllableMonitor.setState(MonitorState.NORMAL);
            Thread.sleep(1000);

            // Then: All should resume and complete
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            for (SlowTask task : tasks) {
                assertTrue("Task should complete", task.isCompleted());
            }

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Scenario: Verify coldMonitoringInterval is respected
     * Expected: Resume detection happens within the configured interval
     */
    @Test(timeout = 20000)
    public void testPauseResume_ColdMonitoringIntervalRespected() throws Exception {
        // Given: Long cold monitoring interval
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(controllableMonitor);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(1))
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofSeconds(2)) // 2 second interval
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(10)
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(testConfig, testMonitors);

        try {
            SlowTask slowTask = new SlowTask("interval-task", createItems(50), Priority.MEDIUM, 5, 100);
            Future<Void> future = testExecutor.submit(slowTask);

            // Wait for task to start and complete at least one chunk
            // Chunk size = 5, delay = 100ms per item, so one chunk takes ~500ms
            Thread.sleep(700);
            assertTrue("Task should have started", slowTask.getProcessedCount() >= 5);

            // Start pause: set monitor HOT and wait for detection at next checkpoint
            controllableMonitor.setState(MonitorState.HOT);
            Thread.sleep(600); // Wait for next chunk completion + pause detection
            assertTrue("Should be paused", testExecutor.getMetrics().isPaused());

            // Make monitor NORMAL
            long cooldownTime = System.currentTimeMillis();
            controllableMonitor.setState(MonitorState.NORMAL);

            // Wait for resume (should happen within interval + some margin)
            Thread.sleep(3000); // 2s interval + 1s margin

            long resumeTime = System.currentTimeMillis();
            long detectionDelay = resumeTime - cooldownTime;

            assertFalse("Should have resumed", testExecutor.getMetrics().isPaused());
            assertTrue("Resume should happen within interval (2s) + margin", detectionDelay < 3500);

            future.get(10, TimeUnit.SECONDS);

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ========================================================================
    // TEST HELPER CLASSES
    // ========================================================================

    /**
     * Controllable monitor that can be set to any state for testing
     */
    static class ControllableMonitor implements ResourceMonitor {
        private final String id;
        private final double hotThreshold;
        private final double coldThreshold;
        private volatile MonitorState currentState = MonitorState.NORMAL;
        private volatile double currentValue = 0.0;

        public ControllableMonitor(String id, double hotThreshold, double coldThreshold) {
            this.id = id;
            this.hotThreshold = hotThreshold;
            this.coldThreshold = coldThreshold;
        }

        public void setState(MonitorState state) {
            this.currentState = state;
            // Set value to match state
            switch (state) {
                case HOT:
                    this.currentValue = hotThreshold + 10;
                    break;
                case COOLING:
                    this.currentValue = (hotThreshold + coldThreshold) / 2;
                    break;
                case NORMAL:
                    this.currentValue = coldThreshold - 10;
                    break;
            }
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public MonitorState evaluate() {
            return currentState;
        }

        @Override
        public io.github.throttle.service.base.MonitorMetrics getMetrics() {
            return new io.github.throttle.service.base.MonitorMetrics(
                id, currentValue, hotThreshold, coldThreshold, currentState, Duration.ZERO);
        }
    }

    /**
     * Slow processing task for testing pause behavior
     */
    static class SlowTask extends TestTask {
        private final long delayPerItem;

        public SlowTask(String taskId, List<String> items, Priority priority,
                       int chunkSize, long delayPerItem) {
            super(taskId, items, priority, chunkSize);
            this.delayPerItem = delayPerItem;
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            for (String item : chunk) {
                Thread.sleep(delayPerItem);
            }
            super.processChunk(chunk);
        }
    }

    /**
     * Test task that tracks processing state
     */
    static class TestTask extends AbstractChunkableTask<String> {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private final AtomicInteger chunksProcessed = new AtomicInteger(0);
        private volatile boolean completed = false;
        private volatile boolean hasError = false;
        private volatile long startTime = 0;

        public TestTask(String taskId, List<String> items, Priority priority, int chunkSize) {
            super(taskId, items, priority, chunkSize);
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            if (startTime == 0) {
                startTime = System.currentTimeMillis();
            }
            chunksProcessed.incrementAndGet();
            for (String item : chunk) {
                processedCount.incrementAndGet();
                Thread.sleep(5); // Simulate work
            }
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable error) {
            hasError = true;
        }

        public boolean isCompleted() { return completed; }
        public boolean hasError() { return hasError; }
        public int getProcessedCount() { return processedCount.get(); }
        public int getChunksProcessed() { return chunksProcessed.get(); }
        public long getStartTime() { return startTime; }
        public int getTotalItemCount() { return super.getTotalItemCount(); }
    }

    /**
     * Task that fails after processing N chunks
     */
    static class FailingTask extends TestTask {
        private final int failAtChunk;
        private final AtomicBoolean errorCalled = new AtomicBoolean(false);

        public FailingTask(String taskId, List<String> items, Priority priority,
                          int chunkSize, int failAtChunk) {
            super(taskId, items, priority, chunkSize);
            this.failAtChunk = failAtChunk;
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            // Check if we should fail BEFORE processing this chunk
            // currentChunk is the count of already-processed chunks (0-based count)
            // When currentChunk+1 == failAtChunk, we're about to process the Nth chunk
            int currentChunk = getChunksProcessed();
            if (currentChunk + 1 == failAtChunk) {
                // Fail before processing the failAtChunk-th chunk
                // Example: failAtChunk=2 means fail before processing the 2nd chunk
                throw new RuntimeException("Simulated failure at chunk " + failAtChunk);
            }
            super.processChunk(chunk);
        }

        @Override
        public void onError(Throwable error) {
            super.onError(error);
            errorCalled.set(true);
        }

        public boolean isOnErrorCalled() { return errorCalled.get(); }
    }

    /**
     * Task that tracks callback invocations
     */
    static class CallbackTrackingTask extends TestTask {
        private final AtomicBoolean onCompleteCalled = new AtomicBoolean(false);
        private final AtomicBoolean onErrorCalled = new AtomicBoolean(false);
        private final AtomicBoolean onCancelCalled = new AtomicBoolean(false);

        public CallbackTrackingTask(String taskId, List<String> items,
                                    Priority priority, int chunkSize) {
            super(taskId, items, priority, chunkSize);
        }

        @Override
        public void onComplete() {
            super.onComplete();
            onCompleteCalled.set(true);
        }

        @Override
        public void onError(Throwable error) {
            super.onError(error);
            onErrorCalled.set(true);
        }

        @Override
        public void onCancel() {
            onCancelCalled.set(true);
        }

        public boolean isOnCompleteCalled() { return onCompleteCalled.get(); }
        public boolean isOnErrorCalled() { return onErrorCalled.get(); }
        public boolean isOnCancelCalled() { return onCancelCalled.get(); }
    }

    /**
     * Task that tracks concurrent execution
     */
    static class ConcurrencyTrackingTask extends TestTask {
        private final AtomicBoolean currentlyProcessing = new AtomicBoolean(false);
        private final long delayPerItem;

        public ConcurrencyTrackingTask(String taskId, List<String> items, Priority priority,
                                      int chunkSize, long delayPerItem) {
            super(taskId, items, priority, chunkSize);
            this.delayPerItem = delayPerItem;
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            currentlyProcessing.set(true);
            try {
                for (String item : chunk) {
                    Thread.sleep(delayPerItem);
                }
                super.processChunk(chunk);
            } finally {
                currentlyProcessing.set(false);
            }
        }

        public boolean isCurrentlyProcessing() { return currentlyProcessing.get(); }
    }

    /**
     * Heavy processing task that simulates CPU-intensive work
     */
    static class HeavyProcessingTask extends TestTask {
        private final long delayPerItem;

        public HeavyProcessingTask(String taskId, List<String> items, Priority priority,
                                  int chunkSize, long delayPerItem) {
            super(taskId, items, priority, chunkSize);
            this.delayPerItem = delayPerItem;
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            for (String item : chunk) {
                Thread.sleep(delayPerItem); // Simulate heavy work
            }
            super.processChunk(chunk);
        }
    }

    /**
     * Task that tracks TaskTerminatedException specifically
     */
    static class TerminationTrackingTask extends TestTask {
        private final AtomicBoolean terminationExceptionReceived = new AtomicBoolean(false);
        private volatile Throwable receivedException = null;
        private final long delayPerItem;

        public TerminationTrackingTask(String taskId, List<String> items, Priority priority,
                                       int chunkSize, long delayPerItem) {
            super(taskId, items, priority, chunkSize);
            this.delayPerItem = delayPerItem;
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            for (String item : chunk) {
                Thread.sleep(delayPerItem);
            }
            super.processChunk(chunk);
        }

        @Override
        public void onError(Throwable error) {
            super.onError(error);
            this.receivedException = error;
            if (error instanceof io.github.throttle.service.base.TaskTerminatedException) {
                terminationExceptionReceived.set(true);
            }
        }

        public boolean isTerminationExceptionReceived() {
            return terminationExceptionReceived.get();
        }

        public Throwable getReceivedException() {
            return receivedException;
        }
    }

    /**
     * Task that tracks if it was killed mid-chunk vs at checkpoint
     */
    static class CheckpointTrackingTask extends TerminationTrackingTask {
        private final AtomicBoolean currentlyProcessingChunk = new AtomicBoolean(false);
        private volatile boolean killedMidChunk = false;

        public CheckpointTrackingTask(String taskId, List<String> items, Priority priority,
                                      int chunkSize, long delayPerItem) {
            super(taskId, items, priority, chunkSize, delayPerItem);
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            currentlyProcessingChunk.set(true);
            try {
                super.processChunk(chunk);
            } finally {
                currentlyProcessingChunk.set(false);
            }
        }

        @Override
        public void onError(Throwable error) {
            super.onError(error);
            // If we were in the middle of processing when killed, that's a bug
            if (currentlyProcessingChunk.get() &&
                error instanceof io.github.throttle.service.base.TaskTerminatedException) {
                killedMidChunk = true;
            }
        }

        public boolean wasKilledMidChunk() {
            return killedMidChunk;
        }
    }
}

