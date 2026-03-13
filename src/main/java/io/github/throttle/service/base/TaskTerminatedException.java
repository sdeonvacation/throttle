package io.github.throttle.service.base;

/**
 * Exception thrown when a task is killed due to excessive pauses.
 * The task receives this exception via onError() callback.
 */
public class TaskTerminatedException extends RuntimeException {
    private final String taskId;
    private final int pauseCount;
    private final int maxPauseCount;

    public TaskTerminatedException(String taskId, int pauseCount, int maxPauseCount) {
        super(String.format("Task %s killed after %d pauses (max allowed: %d)",
                           taskId, pauseCount, maxPauseCount));
        this.taskId = taskId;
        this.pauseCount = pauseCount;
        this.maxPauseCount = maxPauseCount;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getPauseCount() {
        return pauseCount;
    }

    public int getMaxPauseCount() {
        return maxPauseCount;
    }
}

