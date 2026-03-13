package io.github.throttle.simulator.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates memory load by allocating large objects.
 * Designed for Apple M4 Air with 16GB RAM.
 */
@Service
public class MemoryLoadGenerator {
    private static final Logger log = LoggerFactory.getLogger(MemoryLoadGenerator.class);

    private final List<byte[]> allocatedMemory = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Allocate memory to reach target percentage of heap.
     *
     * @param targetMemoryPercent Target memory usage (0-100)
     * @param durationMs Duration to hold memory in milliseconds
     */
    public void generateLoad(int targetMemoryPercent, long durationMs) {
        if (running.get()) {
            log.warn("Memory load generator already running");
            return;
        }

        running.set(true);
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long currentMemory = runtime.totalMemory() - runtime.freeMemory();
        long targetMemory = (maxMemory * targetMemoryPercent) / 100;
        long memoryToAllocate = Math.max(0, targetMemory - currentMemory);

        log.info("Generating memory load: target={}%, duration={}ms, toAllocate={}MB",
            targetMemoryPercent, durationMs, memoryToAllocate / (1024 * 1024));

        try {
            // Allocate memory in chunks
            int chunkSize = 10 * 1024 * 1024; // 10MB chunks
            long allocated = 0;

            while (allocated < memoryToAllocate && running.get()) {
                try {
                    byte[] chunk = new byte[chunkSize];
                    // Touch the memory to ensure it's actually allocated
                    for (int i = 0; i < chunk.length; i += 4096) {
                        chunk[i] = (byte) i;
                    }
                    allocatedMemory.add(chunk);
                    allocated += chunkSize;
                } catch (OutOfMemoryError e) {
                    log.warn("Out of memory, allocated {}MB", allocated / (1024 * 1024));
                    break;
                }
            }

            log.info("Allocated {}MB, holding for {}ms", allocated / (1024 * 1024), durationMs);

            // Hold the memory for specified duration
            Thread.sleep(durationMs);

        } catch (InterruptedException e) {
            log.info("Memory load interrupted");
            Thread.currentThread().interrupt();
        } finally {
            releaseMemory();
        }
    }

    /**
     * Release all allocated memory.
     */
    public void releaseMemory() {
        int size = allocatedMemory.size();
        allocatedMemory.clear();
        System.gc(); // Suggest garbage collection
        running.set(false);
        log.info("Released {} memory chunks", size);
    }

    /**
     * Check if generator is running.
     */
    public boolean isRunning() {
        return running.get();
    }
}
