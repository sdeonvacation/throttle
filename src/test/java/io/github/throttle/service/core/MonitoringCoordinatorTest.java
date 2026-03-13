package io.github.throttle.service.core;

import io.github.throttle.service.monitor.MonitorState;
import io.github.throttle.service.monitor.ResourceMonitor;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for MonitoringCoordinator focusing on state detection
 */
public class MonitoringCoordinatorTest {

    private MonitoringCoordinator coordinator;
    private ControllableMonitor cpuMonitor;
    private ControllableMonitor memoryMonitor;

    @Before
    public void setUp() {
        List<ResourceMonitor> monitors = new ArrayList<>();
        cpuMonitor = new ControllableMonitor("cpu-monitor", 75, 50);
        memoryMonitor = new ControllableMonitor("memory-monitor", 70, 50);
        monitors.add(cpuMonitor);
        monitors.add(memoryMonitor);

        coordinator = new MonitoringCoordinator(monitors);
    }

    /**
     * Test isAnyHot returns true when at least one monitor is HOT
     */
    @Test
    public void testIsAnyHot_TrueWhenOneMonitorHot() {
        // Given: CPU hot, memory normal
        cpuMonitor.setState(MonitorState.HOT);
        memoryMonitor.setState(MonitorState.NORMAL);

        // When
        coordinator.sampleMonitors();

        // Then
        assertTrue("Should detect hot monitor", coordinator.isAnyHot());
        assertFalse("Should not be all normal", coordinator.isAllNormal());
    }

    /**
     * Test isAnyHot returns true when both monitors are HOT
     */
    @Test
    public void testIsAnyHot_TrueWhenBothMonitorsHot() {
        // Given: Both hot
        cpuMonitor.setState(MonitorState.HOT);
        memoryMonitor.setState(MonitorState.HOT);

        // When
        coordinator.sampleMonitors();

        // Then
        assertTrue("Should detect hot monitors", coordinator.isAnyHot());
        assertFalse("Should not be all normal", coordinator.isAllNormal());
    }

    /**
     * Test isAnyHot returns false when all monitors are NORMAL
     */
    @Test
    public void testIsAnyHot_FalseWhenAllNormal() {
        // Given: All normal
        cpuMonitor.setState(MonitorState.NORMAL);
        memoryMonitor.setState(MonitorState.NORMAL);

        // When
        coordinator.sampleMonitors();

        // Then
        assertFalse("Should not detect hot", coordinator.isAnyHot());
        assertTrue("Should be all normal", coordinator.isAllNormal());
    }

    /**
     * Test isAllNormal returns false when one monitor is COOLING
     */
    @Test
    public void testIsAllNormal_FalseWhenOneCooling() {
        // Given: CPU cooling, memory normal
        cpuMonitor.setState(MonitorState.COOLING);
        memoryMonitor.setState(MonitorState.NORMAL);

        // When
        coordinator.sampleMonitors();

        // Then
        assertFalse("Should not be hot", coordinator.isAnyHot());
        assertFalse("Should not be all normal (one COOLING)", coordinator.isAllNormal());
    }

    /**
     * Test state transitions are detected correctly
     */
    @Test
    public void testStateTransitions_DetectedCorrectly() {
        // Initial: All normal
        cpuMonitor.setState(MonitorState.NORMAL);
        memoryMonitor.setState(MonitorState.NORMAL);
        coordinator.sampleMonitors();

        assertFalse("Initially no hot monitors", coordinator.isAnyHot());
        assertTrue("Initially all normal", coordinator.isAllNormal());

        // Transition to HOT
        cpuMonitor.setState(MonitorState.HOT);
        coordinator.sampleMonitors();

        assertTrue("Should detect HOT", coordinator.isAnyHot());
        assertFalse("Not all normal", coordinator.isAllNormal());

        // Transition to COOLING
        cpuMonitor.setState(MonitorState.COOLING);
        coordinator.sampleMonitors();

        assertFalse("COOLING is not HOT", coordinator.isAnyHot());
        assertFalse("COOLING is not NORMAL", coordinator.isAllNormal());

        // Transition back to NORMAL
        cpuMonitor.setState(MonitorState.NORMAL);
        coordinator.sampleMonitors();

        assertFalse("All normal again - no hot", coordinator.isAnyHot());
        assertTrue("All normal again", coordinator.isAllNormal());
    }

    /**
     * Test sampling updates state correctly
     */
    @Test
    public void testSampleMonitors_UpdatesState() {
        // Given: Monitors in various states
        cpuMonitor.setState(MonitorState.HOT);
        memoryMonitor.setState(MonitorState.NORMAL);

        // When: Sample before and after changing monitor
        coordinator.sampleMonitors();
        assertTrue("Should be hot before change", coordinator.isAnyHot());

        cpuMonitor.setState(MonitorState.NORMAL);
        coordinator.sampleMonitors();

        // Then: State should update
        assertFalse("Should not be hot after change", coordinator.isAnyHot());
        assertTrue("Should be all normal after change", coordinator.isAllNormal());
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

