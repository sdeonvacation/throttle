package io.github.throttle.service.base;

import io.github.throttle.service.api.ChunkableTask;

import java.util.Comparator;

/**
 * Comparator for ordering ChunkableTask instances in priority queue.
 * Orders by priority (HIGH > MEDIUM > LOW).
 */
public class ChunkableTaskComparator implements Comparator<ChunkableTask<?>> {

    @Override
    public int compare(ChunkableTask<?> t1, ChunkableTask<?> t2) {
        // Higher priority value = higher priority = should come first
        // So we reverse the comparison (t2 - t1 instead of t1 - t2)
        return Integer.compare(
                t2.getCurrentPriority().getValue(),
                t1.getCurrentPriority().getValue()
        );
    }
}

