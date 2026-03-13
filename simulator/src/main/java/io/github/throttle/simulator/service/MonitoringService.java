package io.github.throttle.simulator.service;

import io.github.throttle.service.api.ThrottleService;
import io.github.throttle.service.base.ExecutorMetrics;
import io.github.throttle.service.base.MonitorMetrics;
import io.github.throttle.service.factory.ThrottleServiceFactory;
import io.github.throttle.service.monitor.ResourceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service that monitors executor and system state and broadcasts to WebSocket clients.
 * Creates a standalone executor with monitors for continuous dashboard metrics.
 */
@Service
public class MonitoringService {
    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final LoadControlService loadControlService;

    private ScheduledExecutorService scheduler;
    private ThrottleService standaloneExecutor; // For dashboard monitoring
    private ThrottleService testExecutor; // For active test monitoring
    private AtomicBoolean monitoring = new AtomicBoolean(false);

    // Persist last test metrics until new test starts
    private long lastTasksCompleted = 0;
    private long lastTasksFailed = 0;
    private long lastTasksKilled = 0;
    private long lastPauseCount = 0;

    public MonitoringService(SimpMessagingTemplate messagingTemplate, LoadControlService loadControlService) {
        this.messagingTemplate = messagingTemplate;
        this.loadControlService = loadControlService;
    }

    /**
     * Initialize standalone executor for continuous monitoring.
     */
    @PostConstruct
    public void initialize() {
        log.info("Initializing MonitoringService with standalone executor for dashboard");

        // Create standalone executor with monitors for dashboard
        // This runs continuously and provides CPU/Memory metrics even when no test is running
        standaloneExecutor = ThrottleServiceFactory.builder()
            .workerExecutorService(Executors.newFixedThreadPool(1)) // Minimal pool, just for monitoring
            .queueCapacity(10)
            .cpuMonitor(75, 50)
            .memoryMonitor(70, 50)
            .build();

        log.info("✓ Created standalone executor with monitors for continuous dashboard metrics");

        // Start continuous monitoring
        startContinuousMonitoring();
    }

    /**
     * Cleanup on shutdown.
     */
    @PreDestroy
    public void cleanup() {
        log.info("Shutting down MonitoringService");
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (standaloneExecutor != null) {
            standaloneExecutor.shutdown();
        }
    }

    /**
     * Start continuous monitoring (called at startup).
     */
    private void startContinuousMonitoring() {
        if (monitoring.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Dashboard-Monitor");
                t.setDaemon(true);
                return t;
            });

            scheduler.scheduleAtFixedRate(() -> {
                try {
                    broadcastUpdate();
                } catch (Exception e) {
                    log.error("Error broadcasting update", e);
                }
            }, 0, 500, TimeUnit.MILLISECONDS);

            log.info("Started continuous monitoring for dashboard");
        }
    }

    /**
     * Start monitoring a test executor.
     * Test executor takes priority for task metrics, but standalone executor continues
     * providing CPU/Memory metrics.
     * RESETS persisted metrics from previous test.
     */
    public void startMonitoring(ThrottleService executor) {
        this.testExecutor = executor;

        // Reset persisted metrics when new test starts
        lastTasksCompleted = 0;
        lastTasksFailed = 0;
        lastTasksKilled = 0;
        lastPauseCount = 0;

        log.info("✓ Registered test executor for monitoring: {}", executor.getClass().getSimpleName());
        log.info("✓ Reset previous test metrics");

        try {
            List<ResourceMonitor> monitors = executor.getMonitors();
            log.info("✓ Test executor has {} resource monitors", monitors.size());

            for (ResourceMonitor monitor : monitors) {
                MonitorMetrics metrics = monitor.getMetrics();
                log.info("  → Monitor: {} = {}% (threshold: hot={}, cold={})",
                    metrics.getMonitorId(),
                    metrics.getCurrentValue(),
                    metrics.getHotThreshold(),
                    metrics.getColdThreshold());
            }
        } catch (Exception e) {
            log.error("✗ Error reading test executor monitors", e);
        }
    }

    /**
     * Stop monitoring test executor.
     * Persists final metrics from the test before clearing the executor reference.
     * Standalone executor continues providing system metrics.
     */
    public void stopMonitoring() {
        if (this.testExecutor != null) {
            // Capture final metrics before clearing executor
            try {
                ExecutorMetrics finalMetrics = testExecutor.getMetrics();
                lastTasksCompleted = finalMetrics.getTasksCompleted();
                lastTasksFailed = finalMetrics.getTasksFailed();
                lastTasksKilled = finalMetrics.getTasksKilled();
                lastPauseCount = finalMetrics.getPauseCount();

                log.info("✓ Stopped monitoring test executor: {}", testExecutor.getClass().getSimpleName());
                log.info("✓ Persisted final metrics - Completed: {}, Failed: {}, Killed: {}, Pauses: {}",
                    lastTasksCompleted, lastTasksFailed, lastTasksKilled, lastPauseCount);
            } catch (Exception e) {
                log.error("✗ Error capturing final metrics", e);
            }

            this.testExecutor = null;
        }
    }

    /**
     * Broadcast current state to all connected clients.
     * Uses standalone executor for CPU/Memory (always available).
     * Uses test executor for task metrics (when test running).
     */
    private void broadcastUpdate() {
        DashboardUpdate update = new DashboardUpdate();

        // ALWAYS get CPU/Memory from standalone executor (library API)
        try {
            List<ResourceMonitor> monitors = standaloneExecutor.getMonitors();

            for (ResourceMonitor monitor : monitors) {
                MonitorMetrics metrics = monitor.getMetrics();
                String monitorId = metrics.getMonitorId();

                if (monitorId.contains("CPU") || monitorId.contains("cpu")) {
                    double cpuValue = metrics.getCurrentValue();
                    update.setCpuUsage(cpuValue);
                    if (cpuValue > 50) {
                        log.debug("CPU from library monitor: {}%", cpuValue);
                    }
                } else if (monitorId.contains("Memory") || monitorId.contains("memory")) {
                    double memValue = metrics.getCurrentValue();
                    update.setMemoryUsage(memValue);
                    if (memValue > 50) {
                        log.debug("Memory from library monitor: {}%", memValue);
                    }
                }
            }
        } catch (Exception e) {
            log.error("✗ Error getting metrics from standalone executor", e);
        }

        // Get task metrics from test executor if available, otherwise use persisted metrics
        if (testExecutor != null) {
            try {
                ExecutorMetrics execMetrics = testExecutor.getMetrics();
                update.setQueueSize(execMetrics.getQueueSize());
                update.setActiveThreads(execMetrics.getActiveThreads());
                update.setTasksCompleted(execMetrics.getTasksCompleted());
                update.setTasksFailed(execMetrics.getTasksFailed());
                update.setTasksKilled(execMetrics.getTasksKilled());
                update.setPauseCount(execMetrics.getPauseCount());
                update.setPaused(testExecutor.isPaused());
            } catch (Exception e) {
                log.error("✗ Error getting metrics from test executor", e);
            }
        } else {
            // No active test - use persisted metrics from last test
            // Queue and active threads reset to 0 (no executor running)
            update.setQueueSize(0);
            update.setActiveThreads(0);
            update.setPaused(false);

            // But task counts persist until new test starts
            update.setTasksCompleted(lastTasksCompleted);
            update.setTasksFailed(lastTasksFailed);
            update.setTasksKilled(lastTasksKilled);
            update.setPauseCount(lastPauseCount);

            log.trace("No test executor active - using persisted metrics (Killed: {})", lastTasksKilled);
        }

        update.setTimestamp(System.currentTimeMillis());

        // Load generator status from LoadControlService
        LoadControlService.LoadControlStatus loadStatus = loadControlService.getStatus();
        update.setCpuLoadActive(loadStatus.isCpuLoadActive());
        update.setMemoryLoadActive(loadStatus.isMemoryLoadActive());

        messagingTemplate.convertAndSend("/topic/monitor", update);
    }

    /**
     * Dashboard update message sent via WebSocket.
     */
    public static class DashboardUpdate {
        private long timestamp;
        private double cpuUsage;
        private double memoryUsage;
        private int queueSize;
        private int activeThreads;
        private long tasksCompleted;
        private long tasksFailed;
        private long tasksKilled;
        private long pauseCount;
        private boolean paused;
        private boolean cpuLoadActive;
        private boolean memoryLoadActive;

        // Getters and setters
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public double getCpuUsage() { return cpuUsage; }
        public void setCpuUsage(double cpuUsage) { this.cpuUsage = cpuUsage; }
        public double getMemoryUsage() { return memoryUsage; }
        public void setMemoryUsage(double memoryUsage) { this.memoryUsage = memoryUsage; }
        public int getQueueSize() { return queueSize; }
        public void setQueueSize(int queueSize) { this.queueSize = queueSize; }
        public int getActiveThreads() { return activeThreads; }
        public void setActiveThreads(int activeThreads) { this.activeThreads = activeThreads; }
        public long getTasksCompleted() { return tasksCompleted; }
        public void setTasksCompleted(long tasksCompleted) { this.tasksCompleted = tasksCompleted; }
        public long getTasksFailed() { return tasksFailed; }
        public void setTasksFailed(long tasksFailed) { this.tasksFailed = tasksFailed; }
        public long getTasksKilled() { return tasksKilled; }
        public void setTasksKilled(long tasksKilled) { this.tasksKilled = tasksKilled; }
        public long getPauseCount() { return pauseCount; }
        public void setPauseCount(long pauseCount) { this.pauseCount = pauseCount; }
        public boolean isPaused() { return paused; }
        public void setPaused(boolean paused) { this.paused = paused; }
        public boolean isCpuLoadActive() { return cpuLoadActive; }
        public void setCpuLoadActive(boolean cpuLoadActive) { this.cpuLoadActive = cpuLoadActive; }
        public boolean isMemoryLoadActive() { return memoryLoadActive; }
        public void setMemoryLoadActive(boolean memoryLoadActive) { this.memoryLoadActive = memoryLoadActive; }
    }
}
