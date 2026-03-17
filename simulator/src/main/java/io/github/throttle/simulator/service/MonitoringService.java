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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Create a new monitoring service with the specified templates and load control service.
     *
     * @param messagingTemplate the WebSocket messaging template
     * @param loadControlService the load control service
     */
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
            .workerThreadPool(Executors.newFixedThreadPool(1)) // Minimal pool, just for monitoring
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
     * Send a log message to all connected dashboard clients.
     *
     * @param message the log message to send
     * @param type the type of log message (info, success, error)
     */
    public void sendLogMessage(String message, String type) {
        Map<String, Object> logMessage = new HashMap<>();
        logMessage.put("type", "log");
        logMessage.put("message", message);
        logMessage.put("level", type);
        logMessage.put("timestamp", System.currentTimeMillis());

        messagingTemplate.convertAndSend("/topic/monitor", logMessage);
    }

    /**
     * Send a log message with info level.
     */
    public void sendInfoLog(String message) {
        sendLogMessage(message, "info");
    }

    /**
     * Send a log message with success level.
     */
    public void sendSuccessLog(String message) {
        sendLogMessage(message, "success");
    }

    /**
     * Send a log message with error level.
     */
    public void sendErrorLog(String message) {
        sendLogMessage(message, "error");
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
                    // Set CPU monitor state based on current value and thresholds
                    if (cpuValue >= metrics.getHotThreshold()) {
                        update.setCpuMonitorState("HOT");
                    } else if (cpuValue >= metrics.getColdThreshold()) {
                        update.setCpuMonitorState("WARNING");
                    } else {
                        update.setCpuMonitorState("NORMAL");
                    }
                    if (cpuValue > 50) {
                        log.debug("CPU from library monitor: {}%", cpuValue);
                    }
                } else if (monitorId.contains("Memory") || monitorId.contains("memory")) {
                    double memValue = metrics.getCurrentValue();
                    update.setMemoryUsage(memValue);
                    // Set memory monitor state based on current value and thresholds
                    if (memValue >= metrics.getHotThreshold()) {
                        update.setMemoryMonitorState("HOT");
                    } else if (memValue >= metrics.getColdThreshold()) {
                        update.setMemoryMonitorState("WARNING");
                    } else {
                        update.setMemoryMonitorState("NORMAL");
                    }
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
        private String cpuMonitorState;
        private String memoryMonitorState;

        // Getters and setters
        /**
         * Get the timestamp of the dashboard update.
         *
         * @return the timestamp
         */
        public long getTimestamp() { return timestamp; }

        /**
         * Set the timestamp of the dashboard update.
         *
         * @param timestamp the timestamp to set
         */
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        /**
         * Get the CPU usage percentage.
         *
         * @return the CPU usage
         */
        public double getCpuUsage() { return cpuUsage; }

        /**
         * Set the CPU usage percentage.
         *
         * @param cpuUsage the CPU usage to set
         */
        public void setCpuUsage(double cpuUsage) { this.cpuUsage = cpuUsage; }

        /**
         * Get the memory usage percentage.
         *
         * @return the memory usage
         */
        public double getMemoryUsage() { return memoryUsage; }

        /**
         * Set the memory usage percentage.
         *
         * @param memoryUsage the memory usage to set
         */
        public void setMemoryUsage(double memoryUsage) { this.memoryUsage = memoryUsage; }

        /**
         * Get the queue size.
         *
         * @return the queue size
         */
        public int getQueueSize() { return queueSize; }

        /**
         * Set the queue size.
         *
         * @param queueSize the queue size to set
         */
        public void setQueueSize(int queueSize) { this.queueSize = queueSize; }

        /**
         * Get the number of active threads.
         *
         * @return the number of active threads
         */
        public int getActiveThreads() { return activeThreads; }

        /**
         * Set the number of active threads.
         *
         * @param activeThreads the number of active threads to set
         */
        public void setActiveThreads(int activeThreads) { this.activeThreads = activeThreads; }

        /**
         * Get the number of tasks completed.
         *
         * @return the number of tasks completed
         */
        public long getTasksCompleted() { return tasksCompleted; }

        /**
         * Set the number of tasks completed.
         *
         * @param tasksCompleted the number of tasks completed to set
         */
        public void setTasksCompleted(long tasksCompleted) { this.tasksCompleted = tasksCompleted; }

        /**
         * Get the number of tasks failed.
         *
         * @return the number of tasks failed
         */
        public long getTasksFailed() { return tasksFailed; }

        /**
         * Set the number of tasks failed.
         *
         * @param tasksFailed the number of tasks failed to set
         */
        public void setTasksFailed(long tasksFailed) { this.tasksFailed = tasksFailed; }

        /**
         * Get the number of tasks killed.
         *
         * @return the number of tasks killed
         */
        public long getTasksKilled() { return tasksKilled; }

        /**
         * Set the number of tasks killed.
         *
         * @param tasksKilled the number of tasks killed to set
         */
        public void setTasksKilled(long tasksKilled) { this.tasksKilled = tasksKilled; }

        /**
         * Get the pause count.
         *
         * @return the pause count
         */
        public long getPauseCount() { return pauseCount; }

        /**
         * Set the pause count.
         *
         * @param pauseCount the pause count to set
         */
        public void setPauseCount(long pauseCount) { this.pauseCount = pauseCount; }

        /**
         * Check if the system is paused.
         *
         * @return true if paused, false otherwise
         */
        public boolean isPaused() { return paused; }

        /**
         * Set the paused state.
         *
         * @param paused the paused state to set
         */
        public void setPaused(boolean paused) { this.paused = paused; }

        /**
         * Check if CPU load is active.
         *
         * @return true if CPU load is active, false otherwise
         */
        public boolean isCpuLoadActive() { return cpuLoadActive; }

        /**
         * Set the CPU load active state.
         *
         * @param cpuLoadActive the CPU load active state to set
         */
        public void setCpuLoadActive(boolean cpuLoadActive) { this.cpuLoadActive = cpuLoadActive; }

        /**
         * Check if memory load is active.
         *
         * @return true if memory load is active, false otherwise
         */
        public boolean isMemoryLoadActive() { return memoryLoadActive; }

        /**
         * Set the memory load active state.
         *
         * @param memoryLoadActive the memory load active state to set
         */
        public void setMemoryLoadActive(boolean memoryLoadActive) { this.memoryLoadActive = memoryLoadActive; }
        public String getCpuMonitorState() { return cpuMonitorState; }
        public void setCpuMonitorState(String cpuMonitorState) { this.cpuMonitorState = cpuMonitorState; }
        public String getMemoryMonitorState() { return memoryMonitorState; }
        public void setMemoryMonitorState(String memoryMonitorState) { this.memoryMonitorState = memoryMonitorState; }
    }
}
