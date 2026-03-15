# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Throttle is a Java library for resource-aware task execution with automatic pause/resume capabilities. It uses chunk-based processing where tasks split work into chunks that serve as checkpoints for pausing when system resources (CPU/memory) are constrained.

**Key Principle**: Tasks execute on a shared thread pool with priority-based scheduling. Each worker thread processes one task's chunks sequentially. Pause detection happens at chunk boundaries (worker-driven); resume detection is handled by a dedicated control plane thread.

## Build and Test Commands

```bash
# Build the project
mvn clean install

# Run tests
mvn test

# Run specific test class
mvn test -Dtest=ClassName

# Run specific test method
mvn test -Dtest=ClassName#methodName

# Build simulator
cd simulator
mvn clean install

# Run simulator application
cd simulator
mvn spring-boot:run

# Run simulator with increased heap (for memory pressure tests)
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx4g"

# Run all simulator test scenarios
cd simulator
./run-tests.sh

# Run specific simulator scenario
./run-tests.sh 1              # By number
./run-tests.sh normal         # By alias
./run-tests.sh flapping       # Edge case scenario
```

## Architecture Overview

### Core Components

**Package structure**: `io.github.throttle.service.*`

1. **api/** - Public interfaces
   - `ThrottleService` - Main entry point, extends ExecutorService
   - `ChunkableTask<T>` - Interface for tasks that support chunk-based execution (extends `RunnableFuture<Void>`)

2. **base/** - Base classes and utilities
   - `AbstractChunkableTask<T>` - Base implementation that extends `FutureTask<Void>`, handles chunk iteration
   - `Priority` - HIGH, MEDIUM, LOW priority levels
   - `ExecutorMetrics` - Metrics (tasksCompleted, tasksFailed, tasksKilled, pauseCount, etc.)
   - `TaskTerminatedException` - Thrown when task exceeds maxPauseCount

3. **core/** - Internal implementation (not for client use)
   - `ThrottleServiceImpl` - Main service implementation
   - `TaskExecutor` - Worker thread logic (data plane: executes chunks, checks pause at boundaries)
   - `ExecutionCoordinator` - Manages pause/resume coordination
   - `MonitoringCoordinator` - Samples resource monitors and determines system state

4. **monitor/** - Resource monitoring
   - `ResourceMonitor` - Interface with `evaluate()` returning HOT/NORMAL
   - `CpuMonitor` - JMX-based CPU monitoring with hot/cold thresholds and hysteresis
   - `MemoryMonitor` - Heap usage monitoring with hot/cold thresholds and hysteresis
   - `MonitorState` - HOT or NORMAL

5. **config/** - Configuration
   - `ThrottleConfig` - Immutable config with queue capacity, thresholds, intervals
   - `OverflowPolicy` - REJECT, DISCARD_OLDEST, BLOCK

6. **factory/** - Builder for creating executor instances
   - `ThrottleServiceFactory.Builder` - Fluent API for configuration

### Key Design Patterns

**Thread-Task Binding**: Each worker thread executes one task from start to finish, processing all chunks before picking the next task. State is preserved throughout.

**Chunk as Checkpoint**: After every chunk, workers call `pauseIfAnyMonitorHot()` to sample monitors at the checkpoint. If ANY monitor is HOT, the system pauses. Workers then call `awaitResume()` and block on a condition variable. Sampling is synchronized and debounced (100ms) to prevent multiple workers from redundantly sampling monitors when hitting checkpoints simultaneously.

**Hybrid Pause/Resume**:
- **Pause detection**: Checkpoint-driven (workers sample monitors only at chunk boundaries, not continuously)
- **Resume detection**: Control-plane-driven (dedicated thread polls every 5s while paused)

**Key Insight**: Monitors are NOT polled continuously. They are only checked when tasks naturally pause between chunks, minimizing monitoring CPU overhead to near-zero.

**Priority Queue**: `PriorityBlockingQueue` with custom comparator sorts by priority (HIGH > MEDIUM > LOW), then FIFO within priority. Anti-starvation mechanism boosts priority incrementally after 2 hours (configurable).

**Task Killing**: If `pauseCount > maxPauseCount` (default 5), task is killed with `TaskTerminatedException`. The `onError()` callback is invoked, and the Future completes exceptionally.

**Idle Worker Wakeup**: Workers block in `priorityQueue.take()` when queue is empty (zero CPU cost). When pause is triggered, `executePause()` interrupts only idle workers (tracked in `idleThreads` set) to immediately move them to `awaitResume()`.

## Creating Tasks

Tasks must extend `AbstractChunkableTask<T>` or implement `ChunkableTask<T>`:

```java
public class MyTask extends AbstractChunkableTask<Item> {
    public MyTask(List<Item> items, Priority priority, int chunkSize) {
        super(items, priority, chunkSize);
    }

    @Override
    public void processChunk(List<Item> chunk) throws Exception {
        // Process items in this chunk
        // This is where your actual work happens
        // Exceptions propagate to executor (task fails)
    }

    @Override
    public void onComplete() {
        // Optional: called when all chunks complete successfully
        // Do NOT call completeTask() here - it's called by the executor
    }

    @Override
    public void onError(Throwable error) {
        // Optional: called when task fails or is killed
        // Handle TaskTerminatedException separately if needed
    }
}
```

**Important**: Tasks are `RunnableFuture<Void>` - they ARE the Future. `submit()` returns the task itself cast to `Future<Void>`. Do NOT call `completeTask()` or `failTask()` from client code - these are internal methods called by `TaskExecutor`.

## Creating an Executor

Use the factory builder:

```java
ThrottleService executor = ThrottleServiceFactory.builder()
    .workerExecutorService(Executors.newFixedThreadPool(5))  // optional, default: 2 threads
    .controlPlaneExecutorService(Executors.newFixedThreadPool(1))  // optional, default: 2 threads
    .queueCapacity(100)
    .cpuMonitor(75, 50)          // hot=75%, cold=50%
    .memoryMonitor(70, 50)
    .hysteresis(Duration.ofSeconds(10))
    .coldMonitoringInterval(Duration.ofSeconds(5))
    .hotMonitoringDebounceInterval(Duration.ofMillis(100))
    .maxPauseCount(5)
    .taskTerminationEnabled(true)
    .starvationThreshold(Duration.ofHours(2))
    .overflowPolicy(OverflowPolicy.REJECT)
    .build();

// Submit tasks
Future<Void> future = executor.submit(myTask);

// Shutdown when done
executor.shutdown();
executor.awaitTermination(30, TimeUnit.SECONDS);
```

## Monitoring and Metrics

```java
// Get current metrics
ExecutorMetrics metrics = executor.getMetrics();
int completed = metrics.getTasksCompleted();
int failed = metrics.getTasksFailed();
int killed = metrics.getTasksKilled();
int pauseCount = metrics.getPauseCount();
boolean paused = metrics.isPaused();

// Get killed tasks for inspection
List<ChunkableTask<?>> killedTasks = executor.getKilledTasks();

// Get monitor states
List<ResourceMonitor> monitors = executor.getMonitors();
for (ResourceMonitor monitor : monitors) {
    MonitorState state = monitor.evaluate();  // HOT or NORMAL
    MonitorMetrics metrics = monitor.getMetrics();
}
```

## Simulator Architecture

The simulator (Spring Boot app) provides 12 test scenarios for validation:
- 7 positive scenarios (normal operation, resource spike, sustained load, memory pressure, task killing, priority scheduling, stress test)
- 5 edge cases (flapping monitor, queue overflow, failing tasks, cascade kill, shutdown under load)

**Key simulator components**:
- `SimulatorController` - REST API and dashboard endpoint
- `ScenarioRunner` - Implements all 12 test scenarios
- `CpuLoadGenerator` / `MemoryLoadGenerator` - Generate actual system load
- `SystemMonitor` - Samples CPU/memory via JMX for dashboard display
- `MonitoringService` - Pushes metrics to dashboard via WebSocket

Simulator dashboard: http://localhost:8080/api/simulator/dashboard

## Important Implementation Notes

1. **Never call task lifecycle methods from client code**: `completeTask()` and `failTask()` are internal methods called by `TaskExecutor`. Clients use `future.get()` to wait for completion.

2. **Callback safety**: If `onComplete()` or `onError()` throw exceptions, they are caught and logged. The Future is always resolved (either successfully or exceptionally).

3. **Monitor fault tolerance**: If `monitor.evaluate()` throws, the exception is caught per-monitor and logged. The system continues with remaining monitors (fail-open).

4. **Shutdown semantics**: `shutdown()` sets a flag and interrupts idle workers. Workers blocked in `awaitResume()` are woken by `executeResume()` (also triggered by shutdown), observe the shutdown flag, and exit cleanly.

5. **Queue overflow**: With `REJECT` policy, `RejectedExecutionException` is thrown on overflow. With `DISCARD_OLDEST`, the oldest queued task is cancelled and the new task is accepted. With `BLOCK`, the caller blocks until space is available.

6. **Anti-starvation**: Tasks waiting > starvationThreshold (default 2 hours) are boosted one priority level at a time (LOW → MEDIUM → HIGH). Checked on-demand after task completion.

## Module System

This is a Java 17+ modular project. The main module descriptor is at `src/main/java/module-info.java`. Exports:
- `io.github.throttle.service.api`
- `io.github.throttle.service.base`
- `io.github.throttle.service.config`
- `io.github.throttle.service.factory`
- `io.github.throttle.service.monitor`

The `core` package is internal and not exported.

## High-Level Design Document

See `DESIGN.md` for detailed architecture diagrams, state machines, and design rationale. Key sections:
- Section 2: Core concepts (chunkable tasks, thread-task binding, priority scheduling, resource-aware pause/resume)
- Section 3: Architecture choices and trade-offs
- Section 4: System behavior under normal and pressure scenarios
- Section 6: Failure handling for all error paths
- Section 7: Performance characteristics
- Section 9: Use cases and practical applications

## Intelligent Features (Planned Enhancements)

See `INTELLIGENT_FEATURES_PROPOSAL.md` for planned enhancements that add intelligence on top of the flexible configuration system.

**Design Philosophy**: All intelligent features must have **minimal overhead** - the executor itself should not consume significant system resources.

### Priority 1: Low-Hanging Fruit (Recommended)
**Overhead**: ~250KB memory, <10ms CPU per task (~0.1-1% overhead)
**Implementation**: 1-2 weeks

1. **Per-Task Metrics** (200KB memory, <0.1ms CPU)
   - Deep visibility into task execution (queue wait, execution time, pause count, efficiency)
   - Bounded ring buffer prevents memory growth
   - Usage: `executor.getRecentTaskMetrics(100)`

2. **Health Scoring** (0 bytes, <1ms per call)
   - At-a-glance system health with component breakdown
   - Pure computation on existing metrics
   - Usage: `executor.getHealth()` → returns score + grade

3. **Statistical Anomaly Detection** (8KB memory, <5ms per task)
   - Catches 90% of outliers using Z-scores (no ML required)
   - Welford's online algorithm for O(1) updates
   - Detects execution time anomalies, excessive pauses, low efficiency

4. **Workload Profiling** (1KB per task type, <0.1ms per task)
   - Learns task characteristics and recommends optimal chunk sizes
   - Online statistics (no raw data storage)
   - Usage: `executor.getWorkloadProfile("TaskType").recommendChunkSize()`

**Key Features**:
- ✅ All use bounded memory (ring buffers, aggregates only)
- ✅ Efficient algorithms (Welford's, online stats)
- ✅ Zero external dependencies
- ✅ Can be completely disabled via feature flags

### What to Avoid (High Overhead)
These features should NOT be implemented due to significant resource consumption:
- ❌ Time-series databases (network I/O, storage costs, query latency)
- ❌ Machine learning models (10-100MB memory, 10-100ms inference)
- ❌ Distributed tracing (network overhead, span storage)
- ❌ Real-time WebSocket dashboards (connection management, continuous push)
- ❌ Reinforcement learning schedulers (complex, high overhead, research only)

### Configuration
```java
// Enable lightweight intelligence features (default: enabled)
ThrottleService executor = factory.builder()
    .enableMetricsCollection(1000)      // Keep last 1000 tasks
    .enableAnomalyDetection(true)
    .enableWorkloadProfiling(true)
    // OR disable entirely for zero overhead:
    .disableIntelligenceFeatures()
    .build();
```

For detailed implementation plans and code examples, see `INTELLIGENT_FEATURES_PROPOSAL.md`.

## Additional Documentation

- `simulator/docs/README.md` - Detailed simulator documentation with architecture and usage guide
- `DESIGN.md` - Comprehensive high-level design document with diagrams and state machines
- `INTELLIGENT_FEATURES_PROPOSAL.md` - Planned enhancements with overhead analysis
- `README.md` - Project overview and quick start guide
- `CONTRIBUTING.md` - Guidelines for contributing to the project

