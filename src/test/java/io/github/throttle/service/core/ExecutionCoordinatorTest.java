package io.github.throttle.service.core;

import io.github.throttle.service.api.ChunkableTask;
import io.github.throttle.service.base.ChunkableTaskComparator;
import io.github.throttle.service.config.ThrottleConfig;
import io.github.throttle.service.monitor.MonitorState;
import io.github.throttle.service.monitor.ResourceMonitor;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

import static org.junit.Assert.*;

/**
 * Unit tests for ExecutionCoordinator, focusing on pause/resume decision logic
 */
public class ExecutionCoordinatorTest {

    private ExecutionCoordinator executionCoordinator;
    private MonitoringCoordinator monitoringCoordinator;
    private PriorityBlockingQueue<ChunkableTask<?>> queue;
    private ControllableMonitor controllableMonitor;

    @Before
    public void setUp() {
        queue = new PriorityBlockingQueue<>(10, new ChunkableTaskComparator());

        // Create controllable monitor
        List<ResourceMonitor> monitors = new ArrayList<>();
        controllableMonitor = new ControllableMonitor("test-monitor", 80, 50);
        monitors.add(controllableMonitor);

        monitoringCoordinator = new MonitoringCoordinator(monitors);

        ThrottleConfig config = ThrottleConfig.builder()
            .queueCapacity(10)
            .coldMonitoringInterval(Duration.ofSeconds(5))
            .maxPauseCount(5)
            .build();

        executionCoordinator = new ExecutionCoordinator(config, queue, monitoringCoordinator, null);
    }

    /**
     * Test shouldPause returns true when monitor is HOT
     */
    @Test
    public void testShouldPause_ReturnsTrueWhenMonitorHot() {
        // Given: Monitor is HOT
        controllableMonitor.setState(MonitorState.HOT);
        monitoringCoordinator.sampleMonitors();

        // When/Then
        assertTrue("Should pause when monitor is HOT", executionCoordinator.shouldPause());
        assertFalse("Initially not paused", executionCoordinator.getIsPaused());
    }

    /**
     * Test shouldPause returns false when already paused
     */
    @Test
    public void testShouldPause_ReturnsFalseWhenAlreadyPaused() {
        // Given: Monitor is HOT and already paused
        controllableMonitor.setState(MonitorState.HOT);
        monitoringCoordinator.sampleMonitors();
        executionCoordinator.executePause();

        // When/Then
        assertFalse("Should not pause again when already paused", executionCoordinator.shouldPause());
        assertTrue("Should be paused", executionCoordinator.getIsPaused());
    }

    /**
     * Test shouldResume returns true when all monitors NORMAL and currently paused
     */
    @Test
    public void testShouldResume_ReturnsTrueWhenMonitorsNormalAndPaused() {
        // Given: Paused state
        controllableMonitor.setState(MonitorState.HOT);
        monitoringCoordinator.sampleMonitors();
        executionCoordinator.executePause();
        assertTrue("Should be paused", executionCoordinator.getIsPaused());

        // When: Monitor becomes NORMAL
        controllableMonitor.setState(MonitorState.NORMAL);
        monitoringCoordinator.sampleMonitors();

        // Then: Should resume
        assertTrue("Should resume when all monitors NORMAL", executionCoordinator.shouldResume());
    }

    /**
     * Test shouldResume returns false when not paused
     */
    @Test
    public void testShouldResume_ReturnsFalseWhenNotPaused() {
        // Given: Not paused, monitors normal
        controllableMonitor.setState(MonitorState.NORMAL);
        monitoringCoordinator.sampleMonitors();

        // When/Then
        assertFalse("Should not resume when not paused", executionCoordinator.shouldResume());
        assertFalse("Should not be paused", executionCoordinator.getIsPaused());
    }

    /**
     * Test shouldResume returns false when still HOT
     */
    @Test
    public void testShouldResume_ReturnsFalseWhenStillHot() {
        // Given: Paused and monitor still HOT
        controllableMonitor.setState(MonitorState.HOT);
        monitoringCoordinator.sampleMonitors();
        executionCoordinator.executePause();

        // Monitor still hot
        monitoringCoordinator.sampleMonitors();

        // When/Then
        assertFalse("Should not resume when still HOT", executionCoordinator.shouldResume());
        assertTrue("Should still be paused", executionCoordinator.getIsPaused());
    }

    /**
     * Test shouldResume returns false when in COOLING state
     */
    @Test
    public void testShouldResume_ReturnsFalseWhenCooling() {
        // Given: Paused
        controllableMonitor.setState(MonitorState.HOT);
        monitoringCoordinator.sampleMonitors();
        executionCoordinator.executePause();

        // When: Monitor transitions to COOLING (not yet NORMAL)
        controllableMonitor.setState(MonitorState.COOLING);
        monitoringCoordinator.sampleMonitors();

        // Then: Should NOT resume yet (must be NORMAL)
        assertFalse("Should not resume when COOLING", executionCoordinator.shouldResume());
        assertTrue("Should still be paused", executionCoordinator.getIsPaused());
    }

    /**
     * Test executePause increments pause count
     */
    @Test
    public void testExecutePause_IncrementsPauseCount() {
        // Given: Initial state
        assertEquals("Initial pause count should be 0", 0, executionCoordinator.getPauseCount());

        // When: Execute pause
        executionCoordinator.executePause();

        // Then: Pause count incremented
        assertEquals("Pause count should be 1", 1, executionCoordinator.getPauseCount());
        assertTrue("Should be paused", executionCoordinator.getIsPaused());
    }

    /**
     * Test executeResume changes state correctly
     */
    @Test
    public void testExecuteResume_ChangesState() {
        // Given: Paused state
        executionCoordinator.executePause();
        assertTrue("Should be paused", executionCoordinator.getIsPaused());

        // When: Resume
        executionCoordinator.executeResume();

        // Then: Not paused
        assertFalse("Should not be paused after resume", executionCoordinator.getIsPaused());
    }

    /**
     * Test multiple pause/resume cycles
     */
    @Test
    public void testMultiplePauseResumeCycles() {
        // Initial state
        assertFalse("Initially not paused", executionCoordinator.getIsPaused());
        assertEquals("Initial pause count 0", 0, executionCoordinator.getPauseCount());

        // Cycle 1
        executionCoordinator.executePause();
        assertTrue("Paused after cycle 1", executionCoordinator.getIsPaused());
        assertEquals("Pause count 1", 1, executionCoordinator.getPauseCount());

        executionCoordinator.executeResume();
        assertFalse("Resumed after cycle 1", executionCoordinator.getIsPaused());
        assertEquals("Pause count still 1", 1, executionCoordinator.getPauseCount());

        // Cycle 2
        executionCoordinator.executePause();
        assertTrue("Paused after cycle 2", executionCoordinator.getIsPaused());
        assertEquals("Pause count 2", 2, executionCoordinator.getPauseCount());

        executionCoordinator.executeResume();
        assertFalse("Resumed after cycle 2", executionCoordinator.getIsPaused());
        assertEquals("Pause count still 2", 2, executionCoordinator.getPauseCount());
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
}

