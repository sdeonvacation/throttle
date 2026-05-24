package io.github.throttle.simulator.tasks;

import io.github.throttle.service.api.ChunkProcessor;
import io.github.throttle.service.base.DelegatingChunkableTask;
import io.github.throttle.service.base.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulator task built with the {@link ChunkProcessor} + {@link DelegatingChunkableTask}
 * pattern instead of subclassing {@link io.github.throttle.service.base.AbstractChunkableTask}.
 *
 * <p>Demonstrates that any class implementing {@code ChunkProcessor} can be wrapped
 * in a {@code DelegatingChunkableTask} to participate in the throttle service without
 * framework coupling.</p>
 */
public class DelegatingSimulatedTask {

    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean errorCallbackFired = new AtomicBoolean(false);
    private final AtomicBoolean cancelCallbackFired = new AtomicBoolean(false);
    private final AtomicInteger chunksProcessed = new AtomicInteger(0);

    private final DelegatingChunkableTask<String> delegate;

    public DelegatingSimulatedTask(String taskId, int itemCount, Priority priority,
                                   int chunkSize, long workPerItemMs) {
        ChunkProcessor<String> processor = new ChunkProcessor<>() {
            @Override
            public void processChunk(List<String> chunk) throws Exception {
                for (String item : chunk) {
                    Thread.sleep(workPerItemMs);
                    // Light CPU work to mirror SimulatedTask
                    double result = 0;
                    for (int i = 0; i < 1000; i++) {
                        result += Math.sqrt(i) * Math.sin(i);
                    }
                    if (result == Double.NEGATIVE_INFINITY) System.out.println(result);
                }
                chunksProcessed.incrementAndGet();
            }

            @Override
            public void onComplete(String id) {
                completed.set(true);
            }

            @Override
            public void onError(String id, Throwable error) {
                errorCallbackFired.set(true);
            }

            @Override
            public void onCancel(String id) {
                cancelCallbackFired.set(true);
            }
        };

        this.delegate = new DelegatingChunkableTask<>(taskId, createItems(itemCount),
            priority, chunkSize, processor);
    }

    /** Returns the underlying task suitable for submission to {@code ThrottleService}. */
    public DelegatingChunkableTask<String> getTask() {
        return delegate;
    }

    public boolean isCompleted()          { return completed.get(); }
    public boolean isErrorCallbackFired() { return errorCallbackFired.get(); }
    public boolean isCancelCallbackFired(){ return cancelCallbackFired.get(); }
    public int getChunksProcessed()       { return chunksProcessed.get(); }
    public String getTaskId()             { return delegate.getTaskId(); }

    private static List<String> createItems(int count) {
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add("item-" + i);
        }
        return items;
    }
}
