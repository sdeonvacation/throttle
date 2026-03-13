package io.github.throttle.simulator.tasks;

import io.github.throttle.service.base.AbstractChunkableTask;
import io.github.throttle.service.base.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A simulated task that fails (throws) when it reaches a configured chunk index.
 * Used in edge-case scenarios to verify the executor stays healthy after task failures.
 */
public class FailingSimulatedTask extends AbstractChunkableTask<String> {

    private final long workPerItemMs;
    private final int failAtChunkIndex; // 0-based; -1 means never fail
    private int chunksProcessed = 0;

    private final AtomicBoolean errorCallbackFired = new AtomicBoolean(false);
    private volatile boolean completed = false;

    public FailingSimulatedTask(String taskId, int itemCount, Priority priority,
                                int chunkSize, long workPerItemMs, int failAtChunkIndex) {
        super(taskId, createItems(itemCount), priority, chunkSize);
        this.workPerItemMs = workPerItemMs;
        this.failAtChunkIndex = failAtChunkIndex;
    }

    @Override
    public void processChunk(List<String> chunk) throws Exception {
        if (failAtChunkIndex >= 0 && chunksProcessed == failAtChunkIndex) {
            throw new RuntimeException("Simulated failure in task " + getTaskId()
                + " at chunk " + chunksProcessed);
        }
        for (String ignored : chunk) {
            Thread.sleep(workPerItemMs);
        }
        chunksProcessed++;
    }

    @Override
    public void onComplete() {
        completed = true;
    }

    @Override
    public void onError(Throwable error) {
        errorCallbackFired.set(true);
    }

    public boolean isCompleted()           { return completed; }
    public boolean isErrorCallbackFired()  { return errorCallbackFired.get(); }
    public int getChunksProcessed()        { return chunksProcessed; }

    private static List<String> createItems(int count) {
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add("item-" + i);
        }
        return items;
    }
}

