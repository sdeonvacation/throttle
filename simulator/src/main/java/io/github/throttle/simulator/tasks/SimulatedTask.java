package io.github.throttle.simulator.tasks;

import io.github.throttle.service.base.Priority;
import io.github.throttle.service.base.AbstractChunkableTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulated task for testing purposes.
 * Performs configurable amount of work per item.
 */
public class SimulatedTask extends AbstractChunkableTask<String> {
    private final long workPerItemMs;
    private volatile long completionTime = 0;
    private volatile boolean completed = false;

    public SimulatedTask(String taskId, int itemCount, Priority priority,
                        int chunkSize, long workPerItemMs) {
        super(taskId, createItems(itemCount), priority, chunkSize);
        this.workPerItemMs = workPerItemMs;
    }

    @Override
    public void processChunk(List<String> chunk) throws Exception {
        for (String item : chunk) {
            // Simulate work by sleeping
            Thread.sleep(workPerItemMs);

            // Also do some CPU work to make it realistic
            double result = 0;
            for (int i = 0; i < 1000; i++) {
                result += Math.sqrt(i) * Math.sin(i);
            }

            // Prevent optimization
            if (result == Double.NEGATIVE_INFINITY) {
                System.out.println(result);
            }
        }
    }

    @Override
    public void onComplete() {
        completed = true;
        completionTime = System.currentTimeMillis();
    }

    @Override
    public void onError(Throwable error) {
        completionTime = System.currentTimeMillis();
    }

    public boolean isCompleted() {
        return completed;
    }

    public long getCompletionTime() {
        return completionTime;
    }

    private static List<String> createItems(int count) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add("item-" + i);
        }
        return items;
    }
}
