package io.github.throttle.service.api;

import java.util.List;

/**
 * Strategy interface for processing chunks of items within a {@link ChunkableTask}.
 *
 * <p>Implementations define the actual work to be done per chunk, and optionally
 * react to task lifecycle events (completion, error, cancellation).</p>
 *
 * <p>This interface removes the need to subclass {@code AbstractChunkableTask} for
 * straightforward use-cases: callers provide a {@code ChunkProcessor} strategy
 * to {@link io.github.throttle.service.base.DelegatingChunkableTask} and receive
 * a fully functional {@code ChunkableTask} in return.</p>
 *
 * @param <T> Type of item being processed
 */
public interface ChunkProcessor<T> {

    /**
     * Process a single chunk of items.
     *
     * @param chunk non-null, non-empty list of items to process
     * @throws Exception if processing fails; the exception propagates to the task framework
     */
    void processChunk(List<T> chunk) throws Exception;

    /**
     * Called when the owning task completes successfully.
     *
     * @param taskId identifier of the completed task
     */
    default void onComplete(String taskId) {}

    /**
     * Called when the owning task fails with an exception.
     *
     * @param taskId identifier of the failed task
     * @param error  the exception that caused the failure
     */
    default void onError(String taskId, Throwable error) {}

    /**
     * Called when the owning task is cancelled.
     *
     * @param taskId identifier of the cancelled task
     */
    default void onCancel(String taskId) {}
}
