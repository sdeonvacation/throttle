package io.github.throttle.service.base;

/**
 * Metrics for monitoring executor service.
 */
public class ExecutorMetrics {
    private final int activeThreads;
    private final int queueSize;
    private final long tasksCompleted;
    private final long tasksFailed;
    private final long tasksKilled;
    private final long pauseCount;
    private final long totalPauseDuration;
    private final boolean isPaused;

    public ExecutorMetrics(int activeThreads, int queueSize, long tasksCompleted,
                          long tasksFailed, long tasksKilled, long pauseCount,
                          long totalPauseDuration, boolean isPaused) {
        this.activeThreads = activeThreads;
        this.queueSize = queueSize;
        this.tasksCompleted = tasksCompleted;
        this.tasksFailed = tasksFailed;
        this.tasksKilled = tasksKilled;
        this.pauseCount = pauseCount;
        this.totalPauseDuration = totalPauseDuration;
        this.isPaused = isPaused;
    }

    public int getActiveThreads() {
        return activeThreads;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public long getTasksCompleted() {
        return tasksCompleted;
    }

    public long getTasksFailed() {
        return tasksFailed;
    }

    public long getTasksKilled() {
        return tasksKilled;
    }

    public long getPauseCount() {
        return pauseCount;
    }

    public long getTotalPauseDuration() {
        return totalPauseDuration;
    }

    public boolean isPaused() {
        return isPaused;
    }

    @Override
    public String toString() {
        return "ExecutorMetrics{" +
                "activeThreads=" + activeThreads +
                ", queueSize=" + queueSize +
                ", tasksCompleted=" + tasksCompleted +
                ", tasksFailed=" + tasksFailed +
                ", tasksKilled=" + tasksKilled +
                ", pauseCount=" + pauseCount +
                ", totalPauseDuration=" + totalPauseDuration +
                ", isPaused=" + isPaused +
                '}';
    }
}

