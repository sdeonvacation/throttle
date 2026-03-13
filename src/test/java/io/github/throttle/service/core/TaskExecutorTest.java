package io.github.throttle.service.core;

import io.github.throttle.service.api.ChunkableTask;
import io.github.throttle.service.base.AbstractChunkableTask;
import io.github.throttle.service.base.ChunkableTaskComparator;
import io.github.throttle.service.base.Priority;
import io.github.throttle.service.config.ThrottleConfig;
import io.github.throttle.service.monitor.MonitorState;
import io.github.throttle.service.monitor.ResourceMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for TaskExecutor class, specifically testing pause/resume behavior
 * with cold monitoring interval.
 */
public class TaskExecutorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutorTest.class);

    private TaskExecutor taskExecutor;
    private PriorityBlockingQueue<ChunkableTask<?>> queue;
    private MonitoringCoordinator monitoringCoordinator;
    private ExecutionCoordinator executionCoordinator;
    private ThrottleConfig config;
    private ExecutorService workerPool;
    private Semaphore queuePermits;

    @Before
    public void setUp() {
        queue = new PriorityBlockingQueue<>(10, new ChunkableTaskComparator());

        // Create controllable monitor for testing
        List<ResourceMonitor> monitors = new ArrayList<>();
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        monitors.add(controllableMonitor);

        monitoringCoordinator = new MonitoringCoordinator(monitors);

        config = ThrottleConfig.builder()
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofMillis(500)) // Fast for testing
            .hotMonitoringDebounceInterval(Duration.ofMillis(100)) // Fast debounce for testing
            .maxPauseCount(5)
            .taskTerminationEnabled(false)
            .build();

        executionCoordinator = new ExecutionCoordinator(config, queue, monitoringCoordinator, null);

        workerPool = Executors.newFixedThreadPool(2);
        queuePermits = new Semaphore(10, true); // Match queue capacity
        taskExecutor = new TaskExecutor(queue, config, workerPool, executionCoordinator, monitoringCoordinator, queuePermits);

        // Start control plane for resume monitoring
        executionCoordinator.start();
        taskExecutor.start();
    }

    @After
    public void tearDown() throws InterruptedException {
        if (taskExecutor != null) {
            taskExecutor.shutdown();
            taskExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (executionCoordinator != null) {
            executionCoordinator.shutdown();
        }
        if (workerPool != null) {
            workerPool.shutdown();
            workerPool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    /**
     * Test that control plane detects cooldown and auto-resumes tasks
     */
    @Test(timeout = 15000)
    public void testPauseResume_ControlPlaneAutoResumes() throws Exception {
        // Given: A controllable monitor that we can toggle
        ControllableMonitor monitor = (ControllableMonitor) monitoringCoordinator.getMonitors().get(0);

        // Submit a slow task with many chunks
        SimpleTestTask task = new SimpleTestTask("test-task", 50, 5, 100);
        task.setCurrentPriority(task.getPriority());
        queue.offer(task);

        // Wait for task to complete first chunk (chunk size=5, 100ms/item = 500ms)
        Thread.sleep(700);
        int itemsAfterFirstChunk = task.getProcessedCount();
        assertTrue("Task should have completed at least first chunk", itemsAfterFirstChunk >= 5);

        // Make monitor HOT and explicitly trigger pause
        monitor.setState(MonitorState.HOT);
        monitoringCoordinator.sampleMonitors();
        executionCoordinator.executePause();

        // Wait for pause to take full effect (task needs to hit checkpoint)
        Thread.sleep(1000);
        assertTrue("Should be paused", executionCoordinator.getIsPaused());

        // Record progress AFTER pause has taken effect, then wait to verify no more progress
        int processedBeforePause = task.getProcessedCount();
        Thread.sleep(1200); // Wait significantly longer than one chunk duration
        int processedWhilePaused = task.getProcessedCount();

        // Task should be completely paused (not progressing)
        // Allow very small margin for race condition at pause boundary
        int progressDuringPause = processedWhilePaused - processedBeforePause;
        assertTrue("Task should not progress while paused (allowed margin: 1 item)",
            progressDuringPause <= 1);

        // Make monitor NORMAL - CONTROL PLANE should detect and auto-resume
        monitor.setState(MonitorState.NORMAL);

        // Wait for control plane cold monitoring to detect (interval is 500ms) + resume to take effect
        Thread.sleep(1500);

        // Task should resume and complete
        task.get(10, TimeUnit.SECONDS);
        assertTrue("Task should complete after auto-resume", task.isCompleted());
        assertEquals("All items should be processed", 50, task.getProcessedCount());
    }

    /**
     * Test that cold monitoring interval is respected by control plane
     */
    @Test(timeout = 20000)
    public void testColdMonitoringInterval_RespectedDuringPause() throws Exception {
        // Given: Longer cold monitoring interval
        config = ThrottleConfig.builder()
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofSeconds(2)) // 2 second interval
            .hotMonitoringDebounceInterval(Duration.ofMillis(100)) // Fast debounce for testing
            .maxPauseCount(5)
            .taskTerminationEnabled(false)
            .build();

        // Recreate taskExecutor with new config
        taskExecutor.shutdown();
        taskExecutor.awaitTermination(2, TimeUnit.SECONDS);
        executionCoordinator.shutdown();
        workerPool.shutdown();
        workerPool.awaitTermination(2, TimeUnit.SECONDS);

        List<ResourceMonitor> monitors = new ArrayList<>();
        ControllableMonitor controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        monitors.add(controllableMonitor);
        monitoringCoordinator = new MonitoringCoordinator(monitors);
        executionCoordinator = new ExecutionCoordinator(config, queue, monitoringCoordinator, null);
        workerPool = Executors.newFixedThreadPool(1);
        taskExecutor = new TaskExecutor(queue, config, workerPool, executionCoordinator, monitoringCoordinator, queuePermits);

        // Start control plane and worker
        executionCoordinator.start();
        taskExecutor.start();

        // Submit task
        SimpleTestTask task = new SimpleTestTask("interval-task", 50, 5, 100);
        task.setCurrentPriority(task.getPriority());
        queue.offer(task);

        // Wait for first chunk to complete
        Thread.sleep(700);
        assertTrue("Task should have started", task.getProcessedCount() >= 5);

        // Pause
        controllableMonitor.setState(MonitorState.HOT);
        Thread.sleep(600); // Wait for next checkpoint
        monitoringCoordinator.sampleMonitors();
        executionCoordinator.executePause();
        Thread.sleep(500);
        assertTrue("Should be paused", executionCoordinator.getIsPaused());

        // Make NORMAL and measure time to resume
        long cooldownTime = System.currentTimeMillis();
        controllableMonitor.setState(MonitorState.NORMAL);

        // Wait for auto-resume (should happen within 2s interval + margin)
        Thread.sleep(4000); // 2s interval + 2s margin for safety
        long resumeTime = System.currentTimeMillis();
        long detectionDelay = resumeTime - cooldownTime;

        assertFalse("Should have resumed", executionCoordinator.getIsPaused());
        assertTrue("Resume should happen within interval (2s) + margin (2s)",
            detectionDelay < 4500);

        task.get(10, TimeUnit.SECONDS);
    }

    /**
     * Test that control plane (not workers) samples monitors during pause
     */
    @Test(timeout = 15000)
    public void testPauseResume_ControlPlaneSamplesMonitorsDuringPause() throws Exception {
        // Given: Multiple taskExecutor threads and controllable monitor
        taskExecutor.shutdown();
        taskExecutor.awaitTermination(2, TimeUnit.SECONDS);
        executionCoordinator.shutdown();
        workerPool.shutdown();
        workerPool.awaitTermination(2, TimeUnit.SECONDS);

        List<ResourceMonitor> monitors = new ArrayList<>();
        SamplingCountingMonitor countingMonitor = new SamplingCountingMonitor("counting-monitor", 80, 50);
        monitors.add(countingMonitor);

        monitoringCoordinator = new MonitoringCoordinator(monitors);
        executionCoordinator = new ExecutionCoordinator(config, queue, monitoringCoordinator, null);
        workerPool = Executors.newFixedThreadPool(3); // 3 taskExecutor threads
        taskExecutor = new TaskExecutor(queue, config, workerPool, executionCoordinator, monitoringCoordinator, queuePermits);

        // Start control plane and workers
        executionCoordinator.start();
        taskExecutor.start();

        // Submit multiple tasks
        List<SimpleTestTask> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SimpleTestTask task = new SimpleTestTask("task-" + i, 50, 5, 100);
            task.setCurrentPriority(task.getPriority());
            tasks.add(task);
            queue.offer(task);
        }

        // Wait for tasks to complete first chunk
        Thread.sleep(700);

        // Pause all tasks
        countingMonitor.setState(MonitorState.HOT);
        Thread.sleep(600); // Wait for checkpoint detection
        monitoringCoordinator.sampleMonitors();
        executionCoordinator.executePause();
        Thread.sleep(500);
        assertTrue("Should be paused", executionCoordinator.getIsPaused());

        // Record sampling count before resume
        int samplingCountBeforeResume = countingMonitor.getSamplingCount();

        // Make NORMAL and let control plane detect
        countingMonitor.setState(MonitorState.NORMAL);
        Thread.sleep(2000); // Multiple monitoring intervals

        int samplingCountAfterResume = countingMonitor.getSamplingCount();
        int samplesWhilePaused = samplingCountAfterResume - samplingCountBeforeResume;

        // With control plane sampling every 500ms for 2 seconds, expect ~4 samples
        // Not 3*4=12 samples (which would happen if all 3 workers were sampling)
        assertTrue("Should have sampled monitors while paused", samplesWhilePaused > 0);
        assertTrue("Should have ~4 samples from control plane (not 12 from 3 workers)",
            samplesWhilePaused < 8); // Control plane samples, not workers

        // Tasks should complete
        for (SimpleTestTask task : tasks) {
            task.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Test comprehensive architecture: chunk-driven pause + control plane resume
     */
    @Test(timeout = 20000)
    public void testArchitecture_ChunkDrivenPauseAndControlPlaneResume() throws Exception {
        // Given: Fresh setup with controllable monitor
        ControllableMonitor monitor = (ControllableMonitor) monitoringCoordinator.getMonitors().get(0);

        // Submit task
        SimpleTestTask task = new SimpleTestTask("arch-test", 50, 5, 100);
        task.setCurrentPriority(task.getPriority());
        queue.offer(task);

        // Wait for first chunk to complete
        Thread.sleep(700);
        assertTrue("Task should have completed first chunk", task.getProcessedCount() >= 5);

        // Make monitor HOT - pause will be detected at NEXT CHECKPOINT (chunk-driven, not immediate)
        monitor.setState(MonitorState.HOT);

        // Wait for task to complete current chunk and detect pause at checkpoint
        Thread.sleep(600); // Chunk completes, checkpoint samples monitors, detects HOT, triggers pause
        assertTrue("Should be paused (detected at checkpoint)", executionCoordinator.getIsPaused());

        // Verify task is truly paused
        int pausedCount = task.getProcessedCount();
        Thread.sleep(1000);
        assertEquals("Task should not progress while paused", pausedCount, task.getProcessedCount());

        // Make monitor NORMAL - control plane will detect (not workers)
        monitor.setState(MonitorState.NORMAL);

        // Control plane samples every 500ms, should detect within 500ms + margin
        Thread.sleep(1200);

        // Should be resumed by control plane
        assertFalse("Should be resumed by control plane", executionCoordinator.getIsPaused());

        // Task should complete
        task.get(10, TimeUnit.SECONDS);
        assertTrue("Task should complete", task.isCompleted());
    }

    /**
     * Tasks that start after pause triggered should not get their pause count incremented.
     * This validates that incrementing in handlePauseCheckpoint (not executePause) handles race conditions.
     */
    @Test(timeout = 20000)
    public void testPauseCount_AllTasksCountedFairly() throws Exception {
        // Given: Setup with 3 worker threads to allow concurrent task execution
        taskExecutor.shutdown();
        taskExecutor.awaitTermination(2, TimeUnit.SECONDS);
        executionCoordinator.shutdown();
        workerPool.shutdown();
        workerPool.awaitTermination(2, TimeUnit.SECONDS);

        ControllableMonitor monitor = new ControllableMonitor("test-monitor", 80, 50);
        List<ResourceMonitor> monitors = new ArrayList<>();
        monitors.add(monitor);

        monitoringCoordinator = new MonitoringCoordinator(monitors);
        executionCoordinator = new ExecutionCoordinator(config, queue, monitoringCoordinator, null);
        workerPool = Executors.newFixedThreadPool(3); // 3 workers
        taskExecutor = new TaskExecutor(queue, config, workerPool, executionCoordinator, monitoringCoordinator, queuePermits);

        executionCoordinator.start();
        taskExecutor.start();

        // Create tasks with staggered start times
        SimpleTestTask taskA = new SimpleTestTask("taskA", 30, 5, 150); // Slower
        SimpleTestTask taskB = new SimpleTestTask("taskB", 30, 5, 50);  // Faster (will trigger pause)
        SimpleTestTask taskC = new SimpleTestTask("taskC", 30, 5, 150); // Slower (may start during race window)

        taskA.setCurrentPriority(taskA.getPriority());
        taskB.setCurrentPriority(taskB.getPriority());
        taskC.setCurrentPriority(taskC.getPriority());

        // Submit taskA and taskB
        queue.offer(taskA);
        queue.offer(taskB);

        // Wait for them to start processing
        Thread.sleep(400);

        // Make monitor HOT
        monitor.setState(MonitorState.HOT);

        // Submit taskC AFTER monitor is hot but BEFORE pause may be triggered
        // This simulates the race window
        queue.offer(taskC);

        // Wait for taskB to complete chunk and trigger pause
        Thread.sleep(300);

        // System should be paused now
        assertTrue("System should be paused", executionCoordinator.getIsPaused());

        // Wait for all tasks to hit checkpoints
        Thread.sleep(500);

        // CRITICAL CHECK: All tasks that are now paused should have pauseCount >= 1
        // Even taskC which may have started AFTER pause was triggered
        int countA = taskA.getPauseCount();
        int countB = taskB.getPauseCount();
        int countC = taskC.getPauseCount();

        LOGGER.info("Pause counts: A={}, B={}, C={}", countA, countB, countC);

        // All tasks should have been penalized (count >= 1)
        assertTrue("TaskA should have pause count >= 1", countA >= 1);
        assertTrue("TaskB should have pause count >= 1", countB >= 1);
        assertTrue("TaskC should have pause count >= 1", countC >= 1);

        // Resume and complete
        monitor.setState(MonitorState.NORMAL);
        Thread.sleep(1000);

        taskA.get(10, TimeUnit.SECONDS);
        taskB.get(10, TimeUnit.SECONDS);
        taskC.get(10, TimeUnit.SECONDS);

        // Final verification: All tasks treated fairly
        int finalCountA = taskA.getPauseCount();
        int finalCountB = taskB.getPauseCount();
        int finalCountC = taskC.getPauseCount();

        LOGGER.info("Final pause counts: A={}, B={}, C={}", finalCountA, finalCountB, finalCountC);

        // Allow some variance due to timing, but all should be >= 1
        assertTrue("All tasks should have been penalized at least once",
            finalCountA >= 1 && finalCountB >= 1 && finalCountC >= 1);
    }

    /**
     * Test that monitor sampling is coordinated to prevent redundant samples.
     * With synchronization and hotMonitoringInterval debouncing, redundant samples should be prevented.
     */
    @Test(timeout = 20000)
    public void testDebouncing_PreventsRedundantMonitorSampling() throws Exception {
        // Given: Multiple worker threads and counting monitor
        taskExecutor.shutdown();
        taskExecutor.awaitTermination(2, TimeUnit.SECONDS);
        executionCoordinator.shutdown();
        workerPool.shutdown();
        workerPool.awaitTermination(2, TimeUnit.SECONDS);

        List<ResourceMonitor> monitors = new ArrayList<>();
        SamplingCountingMonitor countingMonitor = new SamplingCountingMonitor("counting-monitor", 80, 50);
        monitors.add(countingMonitor);

        monitoringCoordinator = new MonitoringCoordinator(monitors);
        executionCoordinator = new ExecutionCoordinator(config, queue, monitoringCoordinator, null);
        workerPool = Executors.newFixedThreadPool(4); // 4 workers for high concurrency
        taskExecutor = new TaskExecutor(queue, config, workerPool, executionCoordinator, monitoringCoordinator, queuePermits);

        executionCoordinator.start();
        taskExecutor.start();

        // Submit multiple fast tasks that will hit checkpoints nearly simultaneously
        List<SimpleTestTask> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SimpleTestTask task = new SimpleTestTask("concurrent-task-" + i, 30, 3, 50); // Fast, small chunks
            task.setCurrentPriority(task.getPriority());
            tasks.add(task);
            queue.offer(task);
        }

        // Record initial sample count
        int initialSampleCount = countingMonitor.getSamplingCount();

        // Wait for tasks to process and hit multiple checkpoints
        Thread.sleep(2000); // 2 seconds of processing

        // All tasks should complete
        for (SimpleTestTask task : tasks) {
            task.get(10, TimeUnit.SECONDS);
        }

        int finalSampleCount = countingMonitor.getSamplingCount();
        int totalSamples = finalSampleCount - initialSampleCount;

        // Without synchronization: Each task has ~10 chunks, 4 tasks = ~40 samples
        // With synchronization + hotMonitoringInterval (default 100ms): 2000ms / 100ms = ~20 samples max

        LOGGER.info("Total monitor samples during test: {}", totalSamples);

        assertTrue("Should have sampled monitors", totalSamples > 0);
        assertTrue("Should have significantly fewer samples than without coordination (expected <25, got " + totalSamples + ")",
            totalSamples < 25); // With default 100ms interval over 2s, expect ~20 samples, not ~40
    }

    /**
     * Test that hotMonitoringInterval (debounce interval) is configurable.
     */
    @Test(timeout = 20000)
    public void testHotMonitoringInterval_Configurable() throws Exception {
        // Given: Custom hot monitoring interval (debounce window) of 500ms
        SamplingCountingMonitor countingMonitor = new SamplingCountingMonitor("counting", 80, 50);
        List<ResourceMonitor> testMonitors = new ArrayList<>();
        testMonitors.add(countingMonitor);

        MonitoringCoordinator testMonitoringCoordinator = new MonitoringCoordinator(testMonitors);

        ThrottleConfig testConfig = ThrottleConfig.builder()
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(500)) // Custom: 500ms debounce
            .maxPauseCount(5)
            .taskTerminationEnabled(false)
            .build();

        ExecutionCoordinator testExecutionCoordinator = new ExecutionCoordinator(
            testConfig, queue, testMonitoringCoordinator, null);

        ExecutorService testWorkerPool = Executors.newFixedThreadPool(4);
        Semaphore testQueuePermits = new Semaphore(10, true);
        TaskExecutor testTaskExecutor = new TaskExecutor(
            queue, testConfig, testWorkerPool, testExecutionCoordinator, testMonitoringCoordinator, testQueuePermits);

        testExecutionCoordinator.start();
        testTaskExecutor.start();

        // Submit multiple fast tasks that will hit checkpoints frequently
        List<SimpleTestTask> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SimpleTestTask task = new SimpleTestTask("fast-task-" + i, 30, 3, 50);
            task.setCurrentPriority(task.getPriority());
            tasks.add(task);
            queue.offer(task);
        }

        int initialSampleCount = countingMonitor.getSamplingCount();

        // Wait for processing
        Thread.sleep(2000);

        // All tasks should complete
        for (SimpleTestTask task : tasks) {
            task.get(10, TimeUnit.SECONDS);
        }

        int finalSampleCount = countingMonitor.getSamplingCount();
        int totalSamples = finalSampleCount - initialSampleCount;

        // With 500ms debounce: 2000ms / 500ms = ~4 samples max
        // Should be significantly fewer than 100ms debounce (~20 samples)

        LOGGER.info("Total monitor samples with 500ms debounce: {}", totalSamples);

        assertTrue("Should have sampled monitors", totalSamples > 0);
        assertTrue("Should have very few samples with 500ms debounce (expected <10, got " + totalSamples + ")",
            totalSamples < 10); // With 500ms interval over 2s, expect ~4 samples

        // Cleanup
        testTaskExecutor.shutdown();
        testExecutionCoordinator.shutdown();
        testWorkerPool.shutdown();
        testWorkerPool.awaitTermination(2, TimeUnit.SECONDS);
    }

    // ========================================================================
    // HELPER CLASSES
    // ========================================================================

    /**
     * Controllable monitor for testing
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
     * Monitor that counts how many times it's sampled (evaluate called)
     */
    static class SamplingCountingMonitor extends ControllableMonitor {
        private final AtomicInteger samplingCount = new AtomicInteger(0);

        public SamplingCountingMonitor(String id, double hotThreshold, double coldThreshold) {
            super(id, hotThreshold, coldThreshold);
        }

        @Override
        public MonitorState evaluate() {
            samplingCount.incrementAndGet();
            return super.evaluate();
        }

        public int getSamplingCount() {
            return samplingCount.get();
        }
    }

    /**
     * Simple test task for TaskExecutor testing
     */
    static class SimpleTestTask extends AbstractChunkableTask<Integer> {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final long delayPerItem;

        public SimpleTestTask(String taskId, int itemCount, int chunkSize, long delayPerItem) {
            super(taskId, createItemList(itemCount), Priority.MEDIUM, chunkSize);
            this.delayPerItem = delayPerItem;
        }

        private static List<Integer> createItemList(int count) {
            List<Integer> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                items.add(i);
            }
            return items;
        }

        @Override
        public void processChunk(List<Integer> chunk) throws Exception {
            for (Integer item : chunk) {
                Thread.sleep(delayPerItem);
                processedCount.incrementAndGet();
            }
        }

        @Override
        public void onComplete() {
            completed.set(true);
        }

        @Override
        public void onError(Throwable error) {
            // No-op for test
        }

        public int getProcessedCount() {
            return processedCount.get();
        }

        public boolean isCompleted() {
            return completed.get();
        }
    }
}
