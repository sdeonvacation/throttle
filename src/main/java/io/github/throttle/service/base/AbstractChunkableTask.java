package io.github.throttle.service.base;

import io.github.throttle.service.api.ChunkableTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for implementing ChunkableTask.
 * Provides common functionality for chunk-based processing.
 *
 * @param <T> Type of item being processed
 */
public abstract class AbstractChunkableTask<T> extends FutureTask<Void> implements ChunkableTask<T> {

    private final String taskId;
    private final List<T> items;
    private final int chunkSize;
    private final Priority priority;
    private final AtomicInteger pauseCount = new AtomicInteger(0);
    private final Iterator<T> iterator;

    private volatile Priority currentPriority;
    private volatile Instant enqueueTime;
    private volatile Instant lastBoostTime;

    /**
     * Create a chunkable task.
     *
     * @param items List of items to process
     * @param priority Task priority
     * @param chunkSize Number of items per chunk
     */
    protected AbstractChunkableTask(List<T> items, Priority priority, int chunkSize) {
        this(UUID.randomUUID().toString(), items, priority, chunkSize);
    }

    /**
     * Create a chunkable task with custom ID.
     *
     * @param taskId Custom task identifier
     * @param items List of items to process
     * @param priority Task priority
     * @param chunkSize Number of items per chunk
     */
    protected AbstractChunkableTask(String taskId, List<T> items, Priority priority, int chunkSize) {
        // FutureTask requires a Callable or Runnable. We pass a no-op Callable since we override run()
        super(() -> null);

        this.taskId = taskId;
        this.items = new ArrayList<>(items);
        this.chunkSize = chunkSize;
        this.priority = priority;
        this.currentPriority = priority;
        this.iterator = this.items.iterator();
    }

    @Override
    public boolean hasMoreChunks() {
        return iterator.hasNext();
    }

    @Override
    public List<T> getNextChunk() {
        List<T> chunk = new ArrayList<>();

        while (iterator.hasNext() && chunk.size() < chunkSize) {
            chunk.add(iterator.next());
        }

        return chunk;
    }

    @Override
    public Priority getPriority() {
        return priority;
    }

    @Override
    public String getTaskId() {
        return taskId;
    }

    @Override
    public int getPauseCount() {
        return pauseCount.get();
    }

    @Override
    public void incrementPauseCount() {
        pauseCount.incrementAndGet();
    }

    /**
     * Get total number of items in this task.
     */
    protected int getTotalItemCount() {
        return items.size();
    }

    /**
     * Get the chunk size for this task.
     */
    protected int getChunkSize() {
        return chunkSize;
    }

    /**
     * Override run() to prevent FutureTask's normal execution model.
     * TaskExecutor handles execution directly via chunk-by-chunk processing with pause points.
     *
     * <p>This method throws UnsupportedOperationException to make it clear that tasks
     * must be submitted to ThrottleService, not a regular ExecutorService.</p>
     */
    @Override
    public void run() {
        throw new UnsupportedOperationException(
            "Task execution is handled by TaskExecutor via chunk-by-chunk processing. " +
            "Submit to ThrottleService, not a regular ExecutorService."
        );
    }

    /**
     * Complete the task successfully.
     *
     * <p><b>Internal method - do not call directly.</b></p>
     * <p>This method is called by the Throttle framework (TaskExecutor) when all
     * chunks have been processed successfully. It completes the Future with a null result.</p>
     *
     * <p>Clients should use {@link #get()} to wait for task completion, not call this method.</p>
     */
    public void completeTask() {
        set(null);
    }

    /**
     * Complete the task with an exception.
     *
     * <p><b>Internal method - do not call directly.</b></p>
     * <p>This method is called by the Throttle framework (TaskExecutor) when the task
     * fails or is killed. It completes the Future exceptionally.</p>
     *
     * <p>Clients should use {@link #get()} to wait for task completion and catch
     * ExecutionException, not call this method directly.</p>
     *
     * @param ex the exception that caused the task to fail
     */
    public void failTask(Throwable ex) {
        setException(ex);
    }

    // Tracking methods for executor

    @Override
    public Instant getEnqueueTime() {
        return enqueueTime;
    }

    @Override
    public void setEnqueueTime(Instant time) {
        this.enqueueTime = time;
    }

    @Override
    public Instant getLastBoostTime() {
        return lastBoostTime;
    }

    @Override
    public void setLastBoostTime(Instant time) {
        this.lastBoostTime = time;
    }

    @Override
    public Priority getCurrentPriority() {
        return currentPriority;
    }

    @Override
    public void setCurrentPriority(Priority priority) {
        this.currentPriority = priority;
    }
}
