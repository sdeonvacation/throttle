package io.github.throttle.service.api;

import io.github.throttle.service.base.Priority;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.RunnableFuture;

/**
 * A task that can be split into chunks for pausable execution.
 * Each chunk serves as a checkpoint where execution can pause/resume.
 *
 * <p>Thread-Task Binding: One thread executes this task from start to finish,
 * processing chunks sequentially with pause checks between chunks.</p>
 *
 * <p>The task implements RunnableFuture<Void>, providing its own Future that
 * completes when all chunks are processed successfully, or completes exceptionally
 * if the task fails or is killed.</p>
 *
 * @param <T> Type of item being processed
 */
public interface ChunkableTask<T> extends RunnableFuture<Void> {
    /**
     * Check if there are more chunks to process.
     * Called by thread before getting next chunk.
     */
    boolean hasMoreChunks();

    /**
     * Get the next chunk of work.
     * Called by thread to get next batch of items to process.
     *
     * @return List of items in this chunk, or empty if no more chunks
     */
    List<T> getNextChunk();

    /**
     * Process a single chunk of items.
     * This method does the actual work.
     *
     * @param chunk List of items to process
     * @throws Exception if processing fails
     */
    void processChunk(List<T> chunk) throws Exception;

    /**
     * Get the priority of this task.
     * Higher priority tasks are scheduled first.
     */
    Priority getPriority();

    /**
     * Get unique identifier for this task.
     */
    String getTaskId();

    /**
     * Get number of times this task has been paused.
     * Used for killing logic.
     */
    int getPauseCount();

    /**
     * Increment pause count.
     * Called by executor when task pauses.
     */
    void incrementPauseCount();

    /**
     * Called when task completes successfully.
     */
    default void onComplete() {}

    /**
     * Called when task is cancelled.
     */
    default void onCancel() {}

    /**
     * Called when task fails with exception.
     */
    default void onError(Throwable error) {}

    /**
     * Get enqueue time (when task was submitted).
     */
    Instant getEnqueueTime();

    /**
     * Set enqueue time (called by executor when task is submitted).
     */
    void setEnqueueTime(Instant time);

    /**
     * Get last boost time (for anti-starvation).
     */
    Instant getLastBoostTime();

    /**
     * Set last boost time (called by executor when priority is boosted).
     */
    void setLastBoostTime(Instant time);

    /**
     * Get current priority (may differ from original if boosted).
     */
    Priority getCurrentPriority();

    /**
     * Set current priority (called by executor for anti-starvation).
     */
    void setCurrentPriority(Priority priority);

    /**
     * Complete the task successfully.
     *
     * <p><b>Internal method - called by Throttle framework only.</b></p>
     * <p>This method is invoked by the TaskExecutor when all chunks have been processed.
     * Clients should not call this method directly - use {@link #get()} to wait
     * for task completion instead.</p>
     */
    void completeTask();

    /**
     * Complete the task with an exception.
     *
     * <p><b>Internal method - called by Throttle framework only.</b></p>
     * <p>This method is invoked by the TaskExecutor when the task fails or is killed.
     * Clients should not call this method directly - use {@link #get()} and catch
     * ExecutionException instead.</p>
     *
     * @param ex the exception that caused the task to fail
     */
    void failTask(Throwable ex);

    /**
     * Default run implementation - not used by ThrottleService.
     *
     * <p>ThrottleService uses chunk-by-chunk execution with pause points,
     * so this default implementation is bypassed. TaskExecutor calls getNextChunk() and
     * processChunk() directly, and completes the Future when done.</p>
     *
     * <p>This implementation is provided for compatibility if the task is ever
     * submitted to a regular ExecutorService, though that's not the intended use.</p>
     */
    @Override
    default void run() {
        try {
            while (hasMoreChunks()) {
                if (Thread.interrupted()) {
                    onCancel();
                    return;
                }

                List<T> chunk = getNextChunk();
                if (chunk == null || chunk.isEmpty()) {
                    break;
                }

                processChunk(chunk);
            }
            onComplete();
        } catch (Exception e) {
            onError(e);
            throw new RuntimeException("Task execution failed", e);
        }
    }
}

