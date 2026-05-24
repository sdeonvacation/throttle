package io.github.throttle.service.base;

import io.github.throttle.service.api.ChunkProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DelegatingChunkableTask}.
 */
public class DelegatingChunkableTaskTest {

    // --- Constructor validation tests ---

    @Test(expected = NullPointerException.class)
    public void constructor_nullTaskId_throws() {
        new DelegatingChunkableTask<>(null, List.of("a"), Priority.MEDIUM, 5, chunk -> {});
    }

    @Test(expected = NullPointerException.class)
    public void constructor_nullItems_throws() {
        new DelegatingChunkableTask<>("t1", null, Priority.MEDIUM, 5, chunk -> {});
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_emptyItems_throws() {
        new DelegatingChunkableTask<>("t1", Collections.emptyList(), Priority.MEDIUM, 5, chunk -> {});
    }

    @Test(expected = NullPointerException.class)
    public void constructor_nullPriority_throws() {
        new DelegatingChunkableTask<>("t1", List.of("a"), null, 5, chunk -> {});
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_zeroChunkSize_throws() {
        new DelegatingChunkableTask<>("t1", List.of("a"), Priority.MEDIUM, 0, chunk -> {});
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_negativeChunkSize_throws() {
        new DelegatingChunkableTask<>("t1", List.of("a"), Priority.MEDIUM, -1, chunk -> {});
    }

    @Test(expected = NullPointerException.class)
    public void constructor_nullProcessor_throws() {
        new DelegatingChunkableTask<>("t1", List.of("a"), Priority.MEDIUM, 5, null);
    }

    @Test
    public void constructor_validArgs_createsTask() {
        DelegatingChunkableTask<String> task = new DelegatingChunkableTask<>(
            "my-task", List.of("a", "b", "c"), Priority.HIGH, 2, chunk -> {}
        );
        assertEquals("my-task", task.getTaskId());
        assertEquals(Priority.HIGH, task.getPriority());
        assertTrue(task.hasMoreChunks());
    }

    // --- Delegation tests ---

    @Test
    public void processChunk_delegatesToProcessor() throws Exception {
        List<String> processedItems = new ArrayList<>();
        ChunkProcessor<String> processor = chunk -> processedItems.addAll(chunk);

        DelegatingChunkableTask<String> task = new DelegatingChunkableTask<>(
            "t1", List.of("a", "b", "c"), Priority.MEDIUM, 10, processor
        );

        List<String> chunk = List.of("x", "y");
        task.processChunk(chunk);

        assertEquals(Arrays.asList("x", "y"), processedItems);
    }

    @Test
    public void processChunk_propagatesException() {
        ChunkProcessor<String> processor = chunk -> {
            throw new IllegalStateException("boom");
        };

        DelegatingChunkableTask<String> task = new DelegatingChunkableTask<>(
            "t1", List.of("a"), Priority.MEDIUM, 10, processor
        );

        try {
            task.processChunk(List.of("a"));
            fail("Expected exception");
        } catch (Exception e) {
            assertEquals("boom", e.getMessage());
            assertTrue(e instanceof IllegalStateException);
        }
    }

    @Test
    public void onComplete_delegatesWithTaskId() {
        AtomicReference<String> receivedId = new AtomicReference<>();
        ChunkProcessor<String> processor = new ChunkProcessor<>() {
            @Override
            public void processChunk(List<String> chunk) {}

            @Override
            public void onComplete(String taskId) {
                receivedId.set(taskId);
            }
        };

        DelegatingChunkableTask<String> task = new DelegatingChunkableTask<>(
            "task-42", List.of("a"), Priority.LOW, 1, processor
        );

        task.onComplete();
        assertEquals("task-42", receivedId.get());
    }

    @Test
    public void onError_delegatesWithTaskIdAndError() {
        AtomicReference<String> receivedId = new AtomicReference<>();
        AtomicReference<Throwable> receivedError = new AtomicReference<>();
        ChunkProcessor<String> processor = new ChunkProcessor<>() {
            @Override
            public void processChunk(List<String> chunk) {}

            @Override
            public void onError(String taskId, Throwable error) {
                receivedId.set(taskId);
                receivedError.set(error);
            }
        };

        DelegatingChunkableTask<String> task = new DelegatingChunkableTask<>(
            "task-err", List.of("a"), Priority.HIGH, 1, processor
        );

        RuntimeException ex = new RuntimeException("test error");
        task.onError(ex);

        assertEquals("task-err", receivedId.get());
        assertSame(ex, receivedError.get());
    }

    @Test
    public void onCancel_delegatesWithTaskId() {
        AtomicReference<String> receivedId = new AtomicReference<>();
        ChunkProcessor<String> processor = new ChunkProcessor<>() {
            @Override
            public void processChunk(List<String> chunk) {}

            @Override
            public void onCancel(String taskId) {
                receivedId.set(taskId);
            }
        };

        DelegatingChunkableTask<String> task = new DelegatingChunkableTask<>(
            "task-cancel", List.of("a"), Priority.MEDIUM, 1, processor
        );

        task.onCancel();
        assertEquals("task-cancel", receivedId.get());
    }

    // --- Chunk iteration tests ---

    @Test
    public void chunking_worksCorrectly() throws Exception {
        List<List<String>> chunks = new ArrayList<>();
        ChunkProcessor<String> processor = chunks::add;

        DelegatingChunkableTask<String> task = new DelegatingChunkableTask<>(
            "t1", List.of("a", "b", "c", "d", "e"), Priority.MEDIUM, 2, processor
        );

        // Process all chunks
        while (task.hasMoreChunks()) {
            List<String> chunk = task.getNextChunk();
            task.processChunk(chunk);
        }

        assertEquals(3, chunks.size());
        assertEquals(Arrays.asList("a", "b"), chunks.get(0));
        assertEquals(Arrays.asList("c", "d"), chunks.get(1));
        assertEquals(List.of("e"), chunks.get(2));
    }

    // --- Default lifecycle no-ops don't throw ---

    @Test
    public void defaultLifecycleMethods_doNotThrow() {
        // Processor with only processChunk implemented (default lifecycle)
        ChunkProcessor<String> processor = chunk -> {};

        DelegatingChunkableTask<String> task = new DelegatingChunkableTask<>(
            "t1", List.of("a"), Priority.LOW, 1, processor
        );

        // Should not throw
        task.onComplete();
        task.onError(new RuntimeException("test"));
        task.onCancel();
    }
}
