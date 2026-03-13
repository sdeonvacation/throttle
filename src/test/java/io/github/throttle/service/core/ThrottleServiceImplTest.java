package io.github.throttle.service.core;

import io.github.throttle.service.api.*;
import io.github.throttle.service.base.AbstractChunkableTask;
import io.github.throttle.service.base.ExecutorMetrics;
import io.github.throttle.service.base.Priority;
import io.github.throttle.service.config.ThrottleConfig;
import io.github.throttle.service.monitor.CpuMonitor;
import io.github.throttle.service.monitor.MemoryMonitor;
import io.github.throttle.service.monitor.ResourceMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Test for ThrottleService implementation.
 */
public class ThrottleServiceImplTest {

    private ThrottleService executor;

    @Before
    public void setUp() {
        List<ResourceMonitor> monitors = new ArrayList<ResourceMonitor>();
        monitors.add(new CpuMonitor(75, 45, Duration.ofSeconds(1)));
        monitors.add(new MemoryMonitor(70, 45, Duration.ofSeconds(1)));

        ThrottleConfig config = ThrottleConfig.builder()
            .queueCapacity(10)
            .maxPauseCount(5)
            .taskTerminationEnabled(true)
            .starvationThreshold(Duration.ofHours(2))
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

    @Test
    public void testTaskSubmission() throws Exception {
        // Given
        List<String> items = createItems(10);
        TestTask task = new TestTask(items, Priority.MEDIUM, 5);

        // When
        Future<Void> future = executor.submit(task);

        // Then
        assertNotNull(future);
        future.get(10, TimeUnit.SECONDS);
        assertTrue(task.isCompleted());
        assertEquals(10, task.getProcessedCount());
    }

    @Test
    public void testPriorityOrdering() throws Exception {
        // Given: More tasks than pool size to force queueing
        List<String> items = createItems(20);

        // Submit multiple LOW priority tasks first to occupy the pool
        List<TestTask> lowTasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestTask task = new TestTask(createItems(30), Priority.LOW, 10);
            lowTasks.add(task);
            executor.submit(task);
        }

        // Wait a bit to ensure low tasks are executing
        Thread.sleep(100);

        // Now submit HIGH priority task - should jump queue
        TestTask highTask = new TestTask(items, Priority.HIGH, 5);
        TestTask anotherLowTask = new TestTask(items, Priority.LOW, 5);

        // When - submit HIGH and LOW tasks while pool is busy
        Future<Void> highFuture = executor.submit(highTask);
        Future<Void> lowFuture = executor.submit(anotherLowTask);

        // Then - high priority should start before the low task that was submitted at same time
        highFuture.get(30, TimeUnit.SECONDS);
        lowFuture.get(30, TimeUnit.SECONDS);

        assertTrue(highTask.isCompleted());
        assertTrue(anotherLowTask.isCompleted());

        // HIGH task should have started before or at same time as the LOW task submitted after it
        assertTrue("High priority task should start before low priority task",
            highTask.getStartTime() <= anotherLowTask.getStartTime());
    }

    @Test
    public void testChunkExecution() throws Exception {
        // Given
        List<String> items = createItems(20);
        TestTask task = new TestTask(items, Priority.MEDIUM, 5);

        // When
        Future<Void> future = executor.submit(task);
        future.get(10, TimeUnit.SECONDS);

        // Then
        assertTrue(task.isCompleted());
        assertEquals(20, task.getProcessedCount());
        assertEquals(4, task.getChunkProcessedCount()); // 20 items / 5 per chunk = 4 chunks
    }

    @Test
    public void testPauseResume() throws Exception {
        // Given
        List<String> items = createItems(10);
        TestTask task = new TestTask(items, Priority.MEDIUM, 2);

        // When
        Future<Void> future = executor.submit(task);
        Thread.sleep(100); // Let it start

        executor.pauseAll();
        assertTrue(executor.isPaused());

        Thread.sleep(500); // Wait a bit

        executor.resumeAll();
        assertFalse(executor.isPaused());

        future.get(10, TimeUnit.SECONDS);

        // Then
        assertTrue(task.isCompleted());
        assertEquals(10, task.getProcessedCount());
    }

    @Test
    public void testMetrics() throws Exception {
        // Given
        List<String> items = createItems(10);
        TestTask task1 = new TestTask(items, Priority.MEDIUM, 5);
        TestTask task2 = new TestTask(items, Priority.MEDIUM, 5);

        // When
        Future<Void> future1 = executor.submit(task1);
        Future<Void> future2 = executor.submit(task2);

        future1.get(10, TimeUnit.SECONDS);
        future2.get(10, TimeUnit.SECONDS);

        // Then
        ExecutorMetrics metrics = executor.getMetrics();
        assertEquals(2, metrics.getTasksCompleted());
        assertEquals(0, metrics.getTasksFailed());
        assertEquals(0, metrics.getTasksKilled());
    }

    @Test
    public void testShutdown() throws Exception {
        // When
        executor.shutdown();

        // Then
        assertTrue(executor.isShutdown());

        // Should reject new tasks
        try {
            executor.submit(new TestTask(createItems(5), Priority.MEDIUM, 5));
            fail("Should throw RejectedExecutionException");
        } catch (Exception e) {
            // Expected
            assertTrue(e.getMessage().contains("shut down"));
        }
    }

    @Test
    public void testMultipleTasks() throws Exception {
        // Given
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        List<TestTask> tasks = new ArrayList<TestTask>();

        // When - submit 10 tasks
        for (int i = 0; i < 10; i++) {
            List<String> items = createItems(10);
            TestTask task = new TestTask(items, Priority.MEDIUM, 5);
            tasks.add(task);
            futures.add(executor.submit(task));
        }

        // Wait for all to complete
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        // Then
        ExecutorMetrics metrics = executor.getMetrics();
        assertEquals(10, metrics.getTasksCompleted());

        for (TestTask task : tasks) {
            assertTrue(task.isCompleted());
            assertEquals(10, task.getProcessedCount());
        }
    }

    /**
     * Test that coldMonitoringInterval configuration is applied correctly
     */
    @Test
    public void testColdMonitoringInterval_ConfigurationApplied() throws Exception {
        // Given: Custom cold monitoring interval
        Duration customInterval = Duration.ofSeconds(3);

        ThrottleConfig customConfig = ThrottleConfig.builder()
            .queueCapacity(10)
            .coldMonitoringInterval(customInterval)
            .maxPauseCount(5)
            .build();

        // When: Config is created
        // Then: Interval should be set correctly
        assertEquals("Cold monitoring interval should be set",
            customInterval, customConfig.getColdMonitoringInterval());
    }

    /**
     * Test default coldMonitoringInterval value
     */
    @Test
    public void testColdMonitoringInterval_DefaultValue() throws Exception {
        // Given: Config without explicit interval
        ThrottleConfig defaultConfig = ThrottleConfig.builder()
            .queueCapacity(10)
            .build();

        // When/Then: Should have default of 5 seconds
        assertEquals("Default cold monitoring interval should be 5 seconds",
            Duration.ofSeconds(5), defaultConfig.getColdMonitoringInterval());
    }

    private List<String> createItems(int count) {
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            items.add("Item-" + i);
        }
        return items;
    }

    /**
     * Test task implementation.
     */
    static class TestTask extends AbstractChunkableTask<String> {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private final AtomicInteger chunkCount = new AtomicInteger(0);
        private volatile boolean completed = false;
        private volatile boolean failed = false;
        private volatile long startTime = 0;

        public TestTask(List<String> items, Priority priority, int chunkSize) {
            super(items, priority, chunkSize);
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            if (startTime == 0) {
                startTime = System.currentTimeMillis();
            }

            chunkCount.incrementAndGet();

            for (String item : chunk) {
                processedCount.incrementAndGet();
                // Simulate some work
                Thread.sleep(10);
            }
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable error) {
            failed = true;
        }

        public boolean isCompleted() {
            return completed;
        }

        public boolean isFailed() {
            return failed;
        }

        public int getProcessedCount() {
            return processedCount.get();
        }

        public int getChunkProcessedCount() {
            return chunkCount.get();
        }

        public long getStartTime() {
            return startTime;
        }
    }
}

