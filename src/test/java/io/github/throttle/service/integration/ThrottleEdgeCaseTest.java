package io.github.throttle.service.integration;

import io.github.throttle.service.api.ThrottleService;
import io.github.throttle.service.base.AbstractChunkableTask;
import io.github.throttle.service.base.ExecutorMetrics;
import io.github.throttle.service.base.Priority;
import io.github.throttle.service.config.ThrottleConfig;
import io.github.throttle.service.config.OverflowPolicy;
import io.github.throttle.service.core.ThrottleServiceImpl;
import io.github.throttle.service.monitor.MonitorState;
import io.github.throttle.service.monitor.ResourceMonitor;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Edge-case and negative-scenario tests for ThrottleService.
 *
 * Covers failure modes that are not in ThrottleIntegrationTest:
 *   - Flapping (rapidly oscillating) monitor
 *   - Monitor that throws during evaluate()
 *   - Task callback (onComplete / onError) that throws
 *   - Queue overflow with DISCARD_OLDEST policy
 *   - Task submitted while the executor is paused
 *   - Single-item / single-chunk boundary
 *   - Future.get() with timeout (task keeps running)
 *   - All tasks fail — metrics consistency
 *   - Shutdown called while the executor is paused (workers blocked in awaitResume)
 */
public class ThrottleEdgeCaseTest {

    private static final Logger LOGGER = Logger.getLogger(ThrottleEdgeCaseTest.class.getName());

    private ThrottleService executor;

    // Default config used by most tests — override per-test when needed
    private ThrottleConfig defaultConfig;

    @Before
    public void setUp() {
        defaultConfig = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(2))
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(500))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(5)
            .taskTerminationEnabled(false)
            .build();

        executor = new ThrottleServiceImpl(defaultConfig, List.of(new AlwaysNormalMonitor()));
    }

    @After
    public void tearDown() throws InterruptedException {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 1. FLAPPING MONITOR
    // =========================================================================

    /**
     * A monitor that rapidly oscillates HOT/NORMAL should not cause tasks to be
     * killed prematurely or the executor to deadlock.  The debounce window must
     * absorb oscillations that are faster than one chunk duration.
     *
     * Expected:
     *  - Executor may pause some cycles but must always resume.
     *  - No tasks are killed (termination disabled).
     *  - All submitted tasks complete eventually.
     */
    @Test(timeout = 30000)
    public void testFlappingMonitor_DoesNotDeadlock() throws Exception {
        FlappingMonitor monitor = new FlappingMonitor("flapping");

        ThrottleConfig cfg = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(2))
            .queueCapacity(20)
            .coldMonitoringInterval(Duration.ofMillis(200))
            .hotMonitoringDebounceInterval(Duration.ofMillis(150))
            .maxPauseCount(10)
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(cfg, List.of(monitor));

        try {
            // Start flapping the monitor every 100ms (faster than debounce window of 150ms)
            ScheduledExecutorService flapper = Executors.newSingleThreadScheduledExecutor();
            AtomicBoolean stopFlapping = new AtomicBoolean(false);
            flapper.scheduleAtFixedRate(() -> {
                if (!stopFlapping.get()) {
                    monitor.flip();
                }
            }, 0, 100, TimeUnit.MILLISECONDS);

            // Submit tasks that take a noticeable amount of time
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                TrackingTask task = new TrackingTask("flap-task-" + i, items(20), Priority.MEDIUM, 5, 50);
                futures.add(testExecutor.submit(task));
            }

            // Let tasks run against the flapping monitor
            // Eventually stop flapping so all tasks can finish
            Thread.sleep(4000);
            stopFlapping.set(true);
            monitor.setState(MonitorState.NORMAL); // ensure it ends NORMAL so resume is triggered
            flapper.shutdown();

            // All tasks must complete (no deadlock, no spurious kill)
            for (Future<Void> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }

            ExecutorMetrics m = testExecutor.getMetrics();
            assertEquals("All tasks should complete", 6, m.getTasksCompleted());
            assertEquals("No tasks should be killed", 0, m.getTasksKilled());
            assertFalse("Executor must not be permanently paused", m.isPaused());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 2. MONITOR THAT THROWS DURING evaluate()
    // =========================================================================

    /**
     * If a monitor's evaluate() throws a RuntimeException the executor must not
     * crash.  Tasks that were running before the monitor broke should still
     * complete, and the pause decision must default to "not paused" (fail-open).
     *
     * Expected:
     *  - Tasks complete.
     *  - Executor stays operational (does not crash or lock up).
     */
    @Test(timeout = 20000)
    public void testFaultyMonitor_ExecutorRemainsOperational() throws Exception {
        ThrowingMonitor faultyMonitor = new ThrowingMonitor("faulty");

        ThrottleConfig cfg = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(2))
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofMillis(300))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(5)
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(cfg, List.of(faultyMonitor));

        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                TrackingTask task = new TrackingTask("faulty-mon-task-" + i, items(10), Priority.MEDIUM, 5, 20);
                futures.add(testExecutor.submit(task));
            }

            // Tasks should complete despite monitor throwing
            for (Future<Void> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }

            ExecutorMetrics m = testExecutor.getMetrics();
            assertEquals("All tasks should complete", 4, m.getTasksCompleted());
            assertEquals("No tasks should fail", 0, m.getTasksFailed());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 3. onComplete() THROWS
    // =========================================================================

    /**
     * If a task's onComplete() callback throws, the executor must catch it
     * gracefully.  The next task in the queue should still be dequeued and
     * executed without the worker thread dying.
     *
     * Expected:
     *  - The bad-callback task is counted as failed (not completed).
     *  - A subsequent normal task still runs and completes.
     */
    @Test(timeout = 15000)
    public void testOnCompleteThrows_WorkerSurvives() throws Exception {
        BadCallbackTask bad = new BadCallbackTask("bad-complete", items(5), Priority.MEDIUM, 5,
            true /* throw in onComplete */, false);
        TrackingTask good = new TrackingTask("good-after-bad", items(5), Priority.MEDIUM, 5, 10);

        Future<Void> badFuture  = executor.submit(bad);
        Future<Void> goodFuture = executor.submit(good);

        // bad future should surface an ExecutionException
        try {
            badFuture.get(10, TimeUnit.SECONDS);
            fail("Should have thrown ExecutionException because onComplete threw");
        } catch (ExecutionException e) {
            assertNotNull("Should have a cause", e.getCause());
            LOGGER.info("Expected exception from bad onComplete: " + e.getCause().getMessage());
        }

        // good task must still run and complete
        goodFuture.get(10, TimeUnit.SECONDS);
        assertTrue("Good task should complete", good.isCompleted());

        ExecutorMetrics m = executor.getMetrics();
        assertEquals("Good task should be counted as completed", 1, m.getTasksCompleted());
    }

    // =========================================================================
    // 4. onError() THROWS (double fault)
    // =========================================================================

    /**
     * If a task's processChunk() throws AND its onError() also throws, the
     * worker must not crash.  Subsequent tasks must still execute.
     *
     * Expected:
     *  - The double-fault task is counted as failed.
     *  - A subsequent normal task still runs and completes.
     */
    @Test(timeout = 15000)
    public void testOnErrorThrows_WorkerSurvives() throws Exception {
        BadCallbackTask bad = new BadCallbackTask("bad-on-error", items(5), Priority.MEDIUM, 5,
            false, true /* throw in onError */);
        TrackingTask good = new TrackingTask("good-after-double-fault", items(5), Priority.MEDIUM, 5, 10);

        Future<Void> badFuture  = executor.submit(bad);
        Future<Void> goodFuture = executor.submit(good);

        try {
            badFuture.get(10, TimeUnit.SECONDS);
            fail("Should have thrown ExecutionException");
        } catch (ExecutionException e) {
            assertNotNull(e.getCause());
        }

        goodFuture.get(10, TimeUnit.SECONDS);
        assertTrue("Good task should complete after double-fault task", good.isCompleted());
    }

    // =========================================================================
    // 5. QUEUE OVERFLOW – DISCARD_OLDEST
    // =========================================================================

    /**
     * When the queue is full and the DISCARD_OLDEST policy is active, the oldest
     * queued task must be discarded (cancelled) and the new task must be accepted.
     *
     * Expected:
     *  - The discarded task's future is cancelled or fails.
     *  - The newly submitted task eventually completes.
     *  - The executor continues operating normally afterwards.
     */
    @Test(timeout = 20000)
    public void testQueueOverflow_DiscardOldest_NewTaskAccepted() throws Exception {
        // One slow worker to keep the single thread busy while we fill the queue
        ThrottleConfig cfg = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(1))
            .queueCapacity(3)
            .overflowPolicy(OverflowPolicy.DISCARD_OLDEST)
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(
            cfg, List.of(new AlwaysNormalMonitor()));

        try {
            // Keep the worker busy
            TrackingTask blocker = new TrackingTask("blocker", items(50), Priority.LOW, 50, 30);
            testExecutor.submit(blocker);
            Thread.sleep(100); // ensure blocker is dequeued and running

            // Fill the queue to capacity (3 slots)
            List<Future<Void>> queuedFutures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                TrackingTask t = new TrackingTask("queued-" + i, items(5), Priority.LOW, 5, 5);
                queuedFutures.add(testExecutor.submit(t));
            }

            // Now submit one more — should discard the oldest queued task
            TrackingTask newest = new TrackingTask("newest", items(5), Priority.HIGH, 5, 5);
            Future<Void> newestFuture = testExecutor.submit(newest);

            // The oldest queued task (queued-0) should be discarded
            // Its future must be cancelled or complete with an error — not hang
            try {
                queuedFutures.get(0).get(5, TimeUnit.SECONDS);
                // If it completes without error that's also fine (was dequeued before discard)
            } catch (CancellationException | ExecutionException e) {
                LOGGER.info("Oldest task was discarded as expected: " + e.getClass().getSimpleName());
            }

            // The newest task must eventually complete
            newestFuture.get(15, TimeUnit.SECONDS);
            assertTrue("Newest task should complete", newest.isCompleted());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 6. TASK SUBMITTED WHILE PAUSED
    // =========================================================================

    /**
     * A task submitted while the executor is paused must wait in the queue
     * and not start executing until the executor resumes.
     *
     * Expected:
     *  - Task makes no progress while paused.
     *  - Task completes after resume.
     */
    @Test(timeout = 20000)
    public void testSubmitWhilePaused_TaskWaitsForResume() throws Exception {
        ControllableMonitor monitor = new ControllableMonitor("ctrl", 80, 50);

        ThrottleConfig cfg = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(2))
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofMillis(300))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(10)
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(cfg, List.of(monitor));

        try {
            // Run a task first to ensure the first chunk checkpoint fires so the
            // debounce timer is set; then set HOT so the next checkpoint pauses
            TrackingTask primeTask = new TrackingTask("prime", items(5), Priority.HIGH, 5, 50);
            testExecutor.submit(primeTask).get(5, TimeUnit.SECONDS);

            // Now trigger a pause via monitor
            monitor.setState(MonitorState.HOT);

            // Submit a slow task — its first chunk will fire the checkpoint and pause
            TrackingTask slowTask = new TrackingTask("slow-before-pause", items(30), Priority.MEDIUM, 5, 80);
            testExecutor.submit(slowTask);

            // Wait for slow task to hit the HOT checkpoint and pause
            Thread.sleep(700);
            assertTrue("Executor should be paused", testExecutor.isPaused());

            // NOW submit a new task while the executor is paused
            TrackingTask lateTask = new TrackingTask("late-submission", items(10), Priority.HIGH, 5, 20);
            testExecutor.submit(lateTask);

            // The late task must not start while paused — verify after a gap
            Thread.sleep(400);
            assertEquals("Late task must not process items while executor is paused",
                0, lateTask.getProcessedCount());

            // Resume and verify late task completes
            monitor.setState(MonitorState.NORMAL);
            lateTask.get(10, TimeUnit.SECONDS);
            assertTrue("Late task should complete after resume", lateTask.isCompleted());
            assertEquals("All items of late task must be processed", 10, lateTask.getProcessedCount());

        } finally {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 7. SINGLE-ITEM TASK
    // =========================================================================

    /**
     * A task with exactly one item (one chunk of size 1) must complete correctly.
     * This exercises the chunk-boundary logic at the minimum possible size.
     *
     * Expected:
     *  - 1 item processed, 1 chunk processed, task completed.
     */
    @Test(timeout = 10000)
    public void testSingleItemTask_CompletesCorrectly() throws Exception {
        TrackingTask task = new TrackingTask("single-item", items(1), Priority.HIGH, 1, 10);
        Future<Void> future = executor.submit(task);
        future.get(5, TimeUnit.SECONDS);

        assertTrue("Task should be completed", task.isCompleted());
        assertEquals("Exactly 1 item processed", 1, task.getProcessedCount());
        assertEquals("Exactly 1 chunk processed", 1, task.getChunksProcessed());
    }

    /**
     * A task whose total items is not an exact multiple of chunk size must
     * process the remainder in the final (smaller) chunk.
     *
     * Expected:
     *  - All 7 items processed across ceil(7/3) = 3 chunks.
     */
    @Test(timeout = 10000)
    public void testUnalignedChunkSize_AllItemsProcessed() throws Exception {
        // 7 items, chunk size 3 → chunks of [3, 3, 1]
        TrackingTask task = new TrackingTask("unaligned", items(7), Priority.MEDIUM, 3, 10);
        Future<Void> future = executor.submit(task);
        future.get(5, TimeUnit.SECONDS);

        assertTrue("Task should complete", task.isCompleted());
        assertEquals("All 7 items processed", 7, task.getProcessedCount());
        assertEquals("3 chunks processed", 3, task.getChunksProcessed());
    }

    // =========================================================================
    // 8. Future.get() TIMEOUT — task keeps running
    // =========================================================================

    /**
     * Calling future.get(timeout) on a long-running task must throw
     * TimeoutException without cancelling the task.  The task must
     * still complete if given enough time afterwards.
     *
     * Expected:
     *  - First get() throws TimeoutException.
     *  - Second get() with longer timeout returns normally.
     *  - Task is completed, not cancelled.
     */
    @Test(timeout = 15000)
    public void testFutureGetTimeout_TaskContinuesRunning() throws Exception {
        // 10 items × 200ms each = 2000ms total
        TrackingTask task = new TrackingTask("long-get", items(10), Priority.MEDIUM, 10, 200);
        Future<Void> future = executor.submit(task);

        // First get — short timeout, must throw TimeoutException
        try {
            future.get(300, TimeUnit.MILLISECONDS);
            fail("Should have thrown TimeoutException");
        } catch (TimeoutException e) {
            // Expected: timeout fires, task not affected
        }

        assertFalse("Task must not be cancelled after get() timeout", future.isCancelled());
        assertFalse("Task must not be done yet", future.isDone());

        // Second get — give it enough time to finish
        future.get(10, TimeUnit.SECONDS);
        assertTrue("Task should still complete after timeout on get()", task.isCompleted());
        assertEquals("All 10 items processed", 10, task.getProcessedCount());
    }

    // =========================================================================
    // 9. ALL TASKS FAIL — metrics consistency
    // =========================================================================

    /**
     * When every submitted task fails, the metrics must be internally consistent:
     *   tasksCompleted == 0, tasksFailed == N, no tasks killed.
     * Workers must continue running (pool not exhausted by exceptions).
     *
     * Expected:
     *  - tasksCompleted = 0
     *  - tasksFailed = total submitted
     *  - Workers still accept new tasks after all failures
     */
    @Test(timeout = 15000)
    public void testAllTasksFail_MetricsConsistent() throws Exception {
        int count = 5;
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            AlwaysFailingTask task = new AlwaysFailingTask("fail-" + i, items(5), Priority.MEDIUM, 5);
            futures.add(executor.submit(task));
        }

        // Collect results — all should surface ExecutionException
        int exceptionCount = 0;
        for (Future<Void> f : futures) {
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                exceptionCount++;
            }
        }

        assertEquals("All futures should raise ExecutionException", count, exceptionCount);

        ExecutorMetrics m = executor.getMetrics();
        assertEquals("tasksCompleted must be 0", 0, m.getTasksCompleted());
        assertEquals("tasksFailed must equal submitted count", count, m.getTasksFailed());
        assertEquals("No tasks should be killed", 0, m.getTasksKilled());

        // Verify the pool is still healthy: a normal task submitted afterwards must complete
        TrackingTask healthy = new TrackingTask("healthy-after-failures", items(5), Priority.HIGH, 5, 10);
        executor.submit(healthy).get(5, TimeUnit.SECONDS);
        assertTrue("Executor should still be functional after all-fail scenario", healthy.isCompleted());
    }

    // =========================================================================
    // 10. SHUTDOWN WHILE PAUSED
    // =========================================================================

    /**
     * If shutdown() is called while the executor is paused, worker threads
     * blocked in awaitResume() must be unblocked and must exit cleanly.
     * The shutdown must complete within a reasonable time (no deadlock).
     *
     * Expected:
     *  - awaitTermination returns true within timeout.
     *  - Executor is terminated.
     */
    @Test(timeout = 15000)
    public void testShutdownWhilePaused_WorkersExitCleanly() throws Exception {
        ControllableMonitor monitor = new ControllableMonitor("ctrl", 80, 50);

        ThrottleConfig cfg = ThrottleConfig.builder()
            .workerThreadPool(Executors.newFixedThreadPool(2))
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofMillis(300))
            .hotMonitoringDebounceInterval(Duration.ofMillis(100))
            .maxPauseCount(10)
            .taskTerminationEnabled(false)
            .build();

        ThrottleService testExecutor = new ThrottleServiceImpl(cfg, List.of(monitor));

        try {
            // Submit a task so workers are active and can hit the checkpoint
            TrackingTask task = new TrackingTask("pause-then-shutdown", items(50), Priority.MEDIUM, 5, 60);
            testExecutor.submit(task); // future intentionally not awaited — shutdown is the focus

            // Let first chunk complete, then trigger pause
            Thread.sleep(400);
            monitor.setState(MonitorState.HOT);
            Thread.sleep(400); // wait for checkpoint to fire and worker to block in awaitResume()

            assertTrue("Executor must be paused before we call shutdown", testExecutor.isPaused());

            // Call shutdown() while workers are blocked — must not deadlock
            long shutdownStart = System.currentTimeMillis();
            testExecutor.shutdown();
            boolean terminated = testExecutor.awaitTermination(5, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - shutdownStart;

            assertTrue("awaitTermination must return true (no deadlock)", terminated);
            assertTrue("Shutdown must complete in under 5s, took " + elapsed + "ms", elapsed < 5500);
            assertTrue("Executor must be shut down", testExecutor.isShutdown());

        } finally {
            // Safe no-op if already shut down
            if (!testExecutor.isShutdown()) {
                testExecutor.shutdown();
            }
        }
    }

    // =========================================================================
    // HELPER CLASSES
    // =========================================================================

    /** Monitor whose state is always NORMAL — used as a neutral background monitor. */
    static class AlwaysNormalMonitor implements ResourceMonitor {
        @Override public String getId() { return "always-normal"; }
        @Override public MonitorState evaluate() { return MonitorState.NORMAL; }
        @Override public io.github.throttle.service.base.MonitorMetrics getMetrics() {
            return new io.github.throttle.service.base.MonitorMetrics(
                "always-normal", 0, 80, 50, MonitorState.NORMAL, Duration.ZERO);
        }
    }

    /** Monitor whose state can be set directly by the test. */
    static class ControllableMonitor implements ResourceMonitor {
        private final String id;
        private final double hot;
        private final double cold;
        private volatile MonitorState state = MonitorState.NORMAL;

        ControllableMonitor(String id, double hot, double cold) {
            this.id = id; this.hot = hot; this.cold = cold;
        }

        void setState(MonitorState s) { this.state = s; }

        @Override public String getId() { return id; }
        @Override public MonitorState evaluate() { return state; }
        @Override public io.github.throttle.service.base.MonitorMetrics getMetrics() {
            return new io.github.throttle.service.base.MonitorMetrics(
                id, state == MonitorState.HOT ? hot + 10 : cold - 10, hot, cold, state, Duration.ZERO);
        }
    }

    /** Monitor that alternates HOT/NORMAL on each flip() call. */
    static class FlappingMonitor implements ResourceMonitor {
        private final String id;
        private final AtomicReference<MonitorState> state =
            new AtomicReference<>(MonitorState.NORMAL);

        FlappingMonitor(String id) { this.id = id; }

        void flip() {
            state.updateAndGet(s -> s == MonitorState.NORMAL ? MonitorState.HOT : MonitorState.NORMAL);
        }

        void setState(MonitorState s) { state.set(s); }

        @Override public String getId() { return id; }
        @Override public MonitorState evaluate() { return state.get(); }
        @Override public io.github.throttle.service.base.MonitorMetrics getMetrics() {
            return new io.github.throttle.service.base.MonitorMetrics(
                id, 0, 80, 50, state.get(), Duration.ZERO);
        }
    }

    /** Monitor whose evaluate() always throws a RuntimeException. */
    static class ThrowingMonitor implements ResourceMonitor {
        private final String id;
        ThrowingMonitor(String id) { this.id = id; }

        @Override public String getId() { return id; }
        @Override public MonitorState evaluate() {
            throw new RuntimeException("Monitor hardware failure simulation");
        }
        @Override public io.github.throttle.service.base.MonitorMetrics getMetrics() {
            return new io.github.throttle.service.base.MonitorMetrics(
                id, 0, 80, 50, MonitorState.NORMAL, Duration.ZERO);
        }
    }

    /** Basic task that counts processed items and chunks. */
    static class TrackingTask extends AbstractChunkableTask<String> {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private final AtomicInteger chunksProcessed = new AtomicInteger(0);
        private volatile boolean completed = false;
        private final long delayPerItem;

        TrackingTask(String id, List<String> items, Priority priority, int chunkSize, long delayPerItem) {
            super(id, items, priority, chunkSize);
            this.delayPerItem = delayPerItem;
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            chunksProcessed.incrementAndGet();
            for (String ignored : chunk) {
                Thread.sleep(delayPerItem);
                processedCount.incrementAndGet();
            }
        }

        @Override public void onComplete() { completed = true; }
        @Override public void onError(Throwable e) { /* no-op */ }

        public boolean isCompleted()    { return completed; }
        public int getProcessedCount()  { return processedCount.get(); }
        public int getChunksProcessed() { return chunksProcessed.get(); }
    }

    /**
     * Task whose onComplete() or onError() throws — simulates a buggy callback.
     * processChunk() always succeeds if throwInOnComplete=true; fails if throwInOnError=true.
     */
    static class BadCallbackTask extends AbstractChunkableTask<String> {
        private final boolean throwInOnComplete;
        private final boolean throwInOnError;

        BadCallbackTask(String id, List<String> items, Priority priority, int chunkSize,
                        boolean throwInOnComplete, boolean throwInOnError) {
            super(id, items, priority, chunkSize);
            this.throwInOnComplete = throwInOnComplete;
            this.throwInOnError    = throwInOnError;
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            if (throwInOnError) {
                // Force a failure so onError() is called
                throw new RuntimeException("Intentional chunk failure");
            }
            Thread.sleep(10);
        }

        @Override
        public void onComplete() {
            if (throwInOnComplete) {
                throw new RuntimeException("Intentional onComplete failure");
            }
        }

        @Override
        public void onError(Throwable e) {
            if (throwInOnError) {
                throw new RuntimeException("Intentional onError failure");
            }
        }
    }

    /** Task whose processChunk() always throws. */
    static class AlwaysFailingTask extends AbstractChunkableTask<String> {
        AlwaysFailingTask(String id, List<String> items, Priority priority, int chunkSize) {
            super(id, items, priority, chunkSize);
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            throw new RuntimeException("Always failing task: " + getTaskId());
        }
    }

    private static List<String> items(int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add("item-" + i);
        return list;
    }
}







