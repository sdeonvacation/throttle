package io.github.throttle.simulator.test;

import io.github.throttle.simulator.monitor.MonitoringStats;

/**
 * Result of a test scenario execution.
 */
public class TestResult {
    private final String scenarioName;
    private boolean success;
    private long duration;
    private long tasksCompleted;
    private long tasksFailed;
    private long tasksKilled;
    private long pauseCount;
    private String error;
    private MonitoringStats monitoringStats;

    public TestResult(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getScenarioName() { return scenarioName; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }
    public long getTasksCompleted() { return tasksCompleted; }
    public void setTasksCompleted(long tasksCompleted) { this.tasksCompleted = tasksCompleted; }
    public long getTasksFailed() { return tasksFailed; }
    public void setTasksFailed(long tasksFailed) { this.tasksFailed = tasksFailed; }
    public long getTasksKilled() { return tasksKilled; }
    public void setTasksKilled(long tasksKilled) { this.tasksKilled = tasksKilled; }
    public long getPauseCount() { return pauseCount; }
    public void setPauseCount(long pauseCount) { this.pauseCount = pauseCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public MonitoringStats getMonitoringStats() { return monitoringStats; }
    public void setMonitoringStats(MonitoringStats stats) { this.monitoringStats = stats; }

    @Override
    public String toString() {
        return String.format(
            "TestResult{scenario='%s', success=%b, duration=%dms, completed=%d, failed=%d, killed=%d, pauses=%d}",
            scenarioName, success, duration, tasksCompleted, tasksFailed, tasksKilled, pauseCount);
    }
}
