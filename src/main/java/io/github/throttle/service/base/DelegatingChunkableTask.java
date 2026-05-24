package io.github.throttle.service.base;

import io.github.throttle.service.api.ChunkProcessor;

import java.util.List;
import java.util.Objects;

/**
 * A {@link AbstractChunkableTask} that delegates chunk processing and lifecycle
 * callbacks to a supplied {@link ChunkProcessor}.
 *
 * <p>This class removes the need to subclass {@code AbstractChunkableTask} for
 * straightforward use-cases: callers provide a {@code ChunkProcessor} strategy
 * and receive a fully functional {@code ChunkableTask} in return.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ChunkProcessor<String> processor = new ChunkProcessor<>() {
 *     @Override
 *     public void processChunk(List<String> chunk) throws Exception {
 *         chunk.forEach(item -> process(item));
 *     }
 * };
 *
 * ChunkableTask<String> task = new DelegatingChunkableTask<>(
 *     "my-task", items, Priority.MEDIUM, 10, processor
 * );
 * throttleService.submit(task);
 * }</pre>
 *
 * @param <T> Type of item being processed
 */
public final class DelegatingChunkableTask<T> extends AbstractChunkableTask<T> {

    private final ChunkProcessor<T> processor;

    /**
     * Create a delegating chunkable task.
     *
     * @param taskId    unique task identifier; must not be null
     * @param items     items to process; must not be null or empty
     * @param priority  task priority; must not be null
     * @param chunkSize number of items per chunk; must be &gt; 0
     * @param processor chunk processor strategy; must not be null
     * @throws NullPointerException     if {@code taskId}, {@code items}, {@code priority},
     *                                  or {@code processor} is null
     * @throws IllegalArgumentException if {@code items} is empty or {@code chunkSize} &lt;= 0
     */
    public DelegatingChunkableTask(String taskId,
                                   List<T> items,
                                   Priority priority,
                                   int chunkSize,
                                   ChunkProcessor<T> processor) {
        super(
            Objects.requireNonNull(taskId, "taskId must not be null"),
            validateItems(items),
            Objects.requireNonNull(priority, "priority must not be null"),
            validateChunkSize(chunkSize)
        );
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
    }

    @Override
    public void processChunk(List<T> chunk) throws Exception {
        processor.processChunk(chunk);
    }

    @Override
    public void onComplete() {
        processor.onComplete(getTaskId());
    }

    @Override
    public void onError(Throwable error) {
        processor.onError(getTaskId(), error);
    }

    @Override
    public void onCancel() {
        processor.onCancel(getTaskId());
    }

    // --- validation helpers ---

    private static <T> List<T> validateItems(List<T> items) {
        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        return items;
    }

    private static int validateChunkSize(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0, was: " + chunkSize);
        }
        return chunkSize;
    }
}
