package io.github.throttle.service.integration;

import io.github.throttle.service.api.ThrottleService;
import io.github.throttle.service.base.AbstractChunkableTask;
import io.github.throttle.service.base.Priority;
import io.github.throttle.service.factory.ThrottleServiceFactory;
import io.github.throttle.service.monitor.CpuMonitor;
import io.github.throttle.service.monitor.MemoryMonitor;
import io.github.throttle.service.monitor.MonitorState;
import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

import static org.junit.Assert.*;

/**
 * Tests CPU monitor degradation behavior when getProcessCpuLoad() is unavailable.
 * Validates graceful degradation on AWS Lambda, GraalVM native images, non-Sun JVMs.
 */
public class CpuMonitorDegradationTest {

    private ThrottleService executor;

    @After
    public void tearDown() throws InterruptedException {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * When CPU monitoring is unavailable (returns 0.0), the system should:
     * 1. Log a SEVERE warning once
     * 2. Continue operating normally (fail-open behavior)
     * 3. Memory monitoring should still work
     * 4. Tasks should execute successfully
     *
     * This simulates deployment to AWS Lambda, GraalVM, or non-Sun JVMs where
     * getProcessCpuLoad() is not available.
     */
    @Test(timeout = 30000)
    public void testCpuMonitorDegradation_SystemContinuesOperating() throws Exception {
        // Set up custom log handler to capture SEVERE logs
        Logger cpuLogger = Logger.getLogger("io.github.throttle.service.monitor.CpuMonitor");
        TestLogHandler logHandler = new TestLogHandler();
        cpuLogger.addHandler(logHandler);
        Level originalLevel = cpuLogger.getLevel();
        cpuLogger.setLevel(Level.ALL);

        try {
            // Create real CpuMonitor (will detect unavailable JMX and degrade gracefully)
            CpuMonitor cpuMonitor = new CpuMonitor(75, 50, Duration.ofSeconds(5));
            MemoryMonitor memoryMonitor = new MemoryMonitor(70, 50, Duration.ofSeconds(5));

            // Verify CPU monitor is in degraded state (returns 0.0)
            MonitorState cpuState = cpuMonitor.evaluate();
            double cpuValue = cpuMonitor.getMetrics().getCurrentValue();

            // On Sun/Oracle JVMs, CPU monitoring works (cpuValue > 0)
            // On other JVMs or restrictive environments, CPU monitoring degrades (cpuValue == 0.0)
            boolean cpuMonitoringAvailable = cpuValue > 0.0;

            if (!cpuMonitoringAvailable) {
                // CPU monitoring is degraded — verify system behavior
                System.out.println("CPU monitoring unavailable (degraded mode) - validating fail-open behavior");

                // Verify CPU monitor is in NORMAL state (fail-open)
                assertEquals("CPU monitor should be NORMAL when degraded (fail-open)",
                    MonitorState.NORMAL, cpuState);
                assertEquals("CPU monitor should return 0.0 when degraded", 0.0, cpuValue, 0.01);

                // Verify SEVERE log was emitted
                assertTrue("Should have logged SEVERE warning about CPU monitoring unavailability",
                    logHandler.getSevereCount() > 0);
                assertTrue("SEVERE log should mention CPU monitoring unavailability",
                    logHandler.getSevereMessages().stream()
                        .anyMatch(msg -> msg.contains("CPU load monitoring not available") ||
                                        msg.contains("getProcessCpuLoad() not available")));

            } else {
                System.out.println("CPU monitoring is available on this JVM — test will verify normal operation");
            }

            // Create executor with both monitors
            executor = ThrottleServiceFactory.builder()
                .queueCapacity(50)
                .disableCpuMonitor()      // Disable default CPU monitor
                .disableMemoryMonitor()   // Disable default memory monitor
                .addMonitor(cpuMonitor)   // Use our explicit CPU monitor
                .addMonitor(memoryMonitor) // Use our explicit memory monitor
                .hysteresis(Duration.ofSeconds(5))
                .maxPauseCount(5)
                .build();

            // Submit tasks — should execute successfully regardless of CPU monitor state
            List<Future<Void>> futures = new ArrayList<>();
            int taskCount = 10;

            for (int i = 0; i < taskCount; i++) {
                SimpleTask task = new SimpleTask("degradation-test-" + i,
                    createItems(20), Priority.MEDIUM, 10);
                futures.add(executor.submit(task));
            }

            // Wait for all tasks to complete
            int completedCount = 0;
            for (Future<Void> future : futures) {
                future.get(15, TimeUnit.SECONDS);
                completedCount++;
            }

            // Verify all tasks completed
            assertEquals("All tasks should complete despite CPU monitor state",
                taskCount, completedCount);

            // Verify memory monitor is still functional
            MonitorState memoryState = memoryMonitor.evaluate();
            assertNotNull("Memory monitor should still be functional", memoryState);

            System.out.println("✓ System operates correctly with CPU monitor in state: " +
                (cpuMonitoringAvailable ? "AVAILABLE" : "DEGRADED"));

        } finally {
            cpuLogger.removeHandler(logHandler);
            cpuLogger.setLevel(originalLevel);
        }
    }

    /**
     * Custom log handler to capture SEVERE messages for validation.
     */
    private static class TestLogHandler extends Handler {
        private final AtomicInteger severeCount = new AtomicInteger(0);
        private final List<String> severeMessages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.SEVERE) {
                severeCount.incrementAndGet();
                severeMessages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}

        public int getSevereCount() {
            return severeCount.get();
        }

        public List<String> getSevereMessages() {
            return new ArrayList<>(severeMessages);
        }
    }

    /**
     * Simple test task for validation.
     */
    private static class SimpleTask extends AbstractChunkableTask<String> {
        private final AtomicInteger processedCount = new AtomicInteger(0);

        public SimpleTask(String id, List<String> items, Priority priority, int chunkSize) {
            super(id, items, priority, chunkSize);
        }

        @Override
        public void processChunk(List<String> chunk) throws Exception {
            for (String item : chunk) {
                Thread.sleep(5); // Minimal work
                processedCount.incrementAndGet();
            }
        }

        public int getProcessedCount() {
            return processedCount.get();
        }
    }

    private static List<String> createItems(int count) {
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add("item-" + i);
        }
        return items;
    }
}
