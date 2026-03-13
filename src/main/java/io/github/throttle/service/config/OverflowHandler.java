package io.github.throttle.service.config;

import io.github.throttle.service.api.ChunkableTask;

/**
 * Custom handler for queue overflow.
 */
public interface OverflowHandler {
    /**
     * Handle overflow situation.
     *
     * @param task The task that cannot be queued
     */
    void handle(ChunkableTask<?> task);
}

