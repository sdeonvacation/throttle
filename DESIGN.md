# Throttle Service - High-Level Design (HLD)

**Version:** 3.0  
**Date:** March 4, 2026  
**Status:** Current Implementation

---

## Executive Summary

A self-regulating executor service with **priority-based task scheduling**, **resource-aware pause/resume**, and **chunk-based execution**. Tasks are executed on a shared thread pool where each thread processes one task's chunks sequentially, enabling pausable execution at chunk boundaries.

**Key Principle:** Single shared thread pool executes prioritized chunkable tasks with pause points between chunks. Pause detection is chunk-driven (by worker threads at checkpoints); resume detection is handled by a dedicated control plane thread.

---

## 1. Core Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  CLIENT APPLICATION                             │
│  (Data Processing, Batch Jobs, Analytics)                       │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           │ submit(task) → Future<Void>
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              ADAPTIVE EXECUTOR SERVICE                          │
│                                                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  RESOURCE MONITORS (client-provided or built-in)           │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │ │
│  │  │ CPU Monitor  │  │Memory Monitor│  │Custom Monitor│      │ │
│  │  │ Hot: 75%     │  │ Hot: 70%     │  │  (Optional)  │      │ │
│  │  │ Cold: 50%    │  │ Cold: 50%    │  │              │      │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘      │ │
│  │  ANY HOT → PAUSE ALL | ALL NORMAL → RESUME                 │ │
│  └────────────────────────────────────────────────────────────┘ │
│                               ↕                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  PRIORITY TASK QUEUE                                       │ │
│  │  (Capacity: configurable, default 100)                     │ │
│  │                                                            │ │
│  │  [HIGH]   DataProcessingTask(batch1)   wait: 2s            │ │
│  │  [HIGH]   CleanupTask(batch2)          wait: 5s            │ │
│  │  [MEDIUM] AnalyticsTask(batch3)        wait: 10s           │ │
│  │  [LOW]    ArchiveTask(batch4)          wait: 35s → BOOST   │ │
│  │  ... (more tasks waiting)                                  │ │
│  └────────────────────────────────────────────────────────────┘ │
│                               ↓                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  DATA PLANE — TASK EXECUTOR (Worker Thread Pool)           │ │
│  │  Default pool size: 2 | Client-provided pool: any size     │ │
│  │                                                            │ │
│  │  Worker-1 → DataProcessingTask(HIGH)                       │ │
│  │             ├─ chunk 1: process items 1-10                 │ │
│  │             ├─ [CHECKPOINT] sample monitors                │ │
│  │             ├─ chunk 2: process items 11-20                │ │
│  │             └─ [CHECKPOINT] sample monitors                │ │
│  │                                                            │ │
│  │  Worker-2 → CleanupTask(HIGH)                              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                               ↕                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  CONTROL PLANE — EXECUTION COORDINATOR                     │ │
│  │  (Dedicated thread pool, default size 2)                   │ │
│  │                                                            │ │
│  │  ├─ Monitors for RESUME while system is paused             │ │
│  │  ├─ Samples monitors every coldMonitoringInterval (5s)     │ │
│  │  ├─ Detects ALL NORMAL → executeResume() → signalAll()     │ │
│  │  ├─ Handles anti-starvation (priority boosting)            │ │
│  │  └─ Wires TaskExecutor reference for idle-thread wakeup    │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Key Design Concepts

### 2.1 Chunkable Tasks

**Concept:** Tasks split work into chunks that serve as **checkpoints** for pause/resume.

```
DataProcessingTask for 100 items:
┌────────────────────────────────────────┐
│ Total: 100 Items                       │
│ Chunk Size: 10 items                   │
│                                        │
│ Chunk 1: [Item1..Item10]  ──→ Process  │
│           ↓                            │
│         [CHECKPOINT] ← Pause possible  │
│           ↓                            │
│ Chunk 2: [Item11..Item20] ──→ Process  │
│           ↓                            │
│         [CHECKPOINT] ← Pause possible  │
│           ↓                            │
│ Chunk 3: [Item21..Item30] ──→ Process  │
│   ...                                  │
└────────────────────────────────────────┘
```

**`ChunkableTask` extends `RunnableFuture<Void>`** — the task IS the future. No wrapper is needed. Clients capture the `Future<Void>` returned by `submit()` directly.

**Benefits:**
- **Pausable**: Can pause between chunks when resources are constrained
- **Resumable**: Resume from next chunk when resources recover
- **Granular**: Control how often pause checks occur
- **No work lost**: Each chunk completes before pausing

### 2.2 Thread-Task Binding

**Concept**: Each worker thread processes ONE task from start to finish.

```
Priority Queue: [Task1(HIGH), Task2(HIGH), Task3(MED), ..., Task100(LOW)]
Worker Pool: 2 threads (default)

Thread-1 ← Task1 (HIGH): process all chunks, then pick Task3
Thread-2 ← Task2 (HIGH): process all chunks, then pick Task4
```

**Benefits:**
- State preservation, resource locality, simple pause logic

### 2.3 Priority-Based Scheduling

```
Priority Levels: HIGH > MEDIUM > LOW
FIFO within same priority (older task runs first)
```

### 2.4 Anti-Starvation with Priority Boosting

**Incremental boost (one level at a time), checked on-demand after task completion:**

```
LOW waiting > threshold (2h) → boost LOW → MEDIUM
MEDIUM waiting > threshold (2h) → boost MEDIUM → HIGH
```

### 2.5 Resource-Aware Pause/Resume — Hybrid Architecture

**Key Principle**: Monitors are NOT polled continuously. They are only checked at natural pause points (checkpoints between chunks), ensuring near-zero monitoring CPU overhead.

**Pause detection is checkpoint-driven:**

```
After every chunk (at checkpoint):
  worker → pauseIfAnyMonitorHot()
         → synchronized + debounced (100ms debounce window)
         → sampleMonitors() only if debounce window expired
         → ANY HOT? → executePause()
                       → isPaused = true
                       → interruptIdleWorkers()  ← wake workers in take()

Note: 100ms debounce prevents redundant sampling when multiple workers
hit checkpoints simultaneously. This is NOT a polling interval.
```

**Resume detection is control-plane-driven:**

```
Control plane (while isPaused):
  → sampleMonitors() every coldMonitoringInterval (5s default)
  → ALL NORMAL? → executeResume()
                → isPaused = false
                → resumeCondition.signalAll()  ← wake all blocked workers

Note: This is the ONLY continuous polling in the system, and it only
happens while paused. During normal execution, monitoring overhead is zero.
```

**Idle worker wakeup on pause:**

Workers block in `priorityQueue.take()` when the queue is empty. When a pause is triggered, `executePause()` calls `taskExecutor.interruptIdleWorkers()`, which interrupts **only** threads currently registered as idle (i.e., inside `take()`). Busy threads (mid-chunk) are not interrupted — they observe the pause flag at their next checkpoint. The interrupted idle workers call `awaitResume()` and block on the condition variable.

### 2.6 Chunk as Checkpoint

```java
// Worker execution loop
while (!shutdown.get()) {
    idleThreads.add(currentThread);        // register as idle
    task = priorityQueue.take();           // block until work arrives
    idleThreads.remove(currentThread);     // deregister — now busy

    if (isPaused()) {
        priorityQueue.put(task);           // return task — haven't started it
        awaitResume();                     // block on condition variable
        continue;
    }

    executeTask(task);
}

// Inside executeTask — chunk loop
while (task.hasMoreChunks()) {
    if (shutdown.get()) { task.cancel(); return; }

    List<T> chunk = task.getNextChunk();
    task.processChunk(chunk);             // actual work

    // CHECKPOINT
    pauseIfAnyMonitorHot();               // sample monitors (synchronized, debounced)
    handlePauseCheckpoint(task);          // if paused: incrementPauseCount + awaitResume
    handleTerminationCondition(task);     // if pauseCount > max: kill task
}

task.onComplete();     // client callback (isolated — future resolved regardless)
task.completeTask();   // resolves Future<Void>
```

**Key safety properties:**
- `onComplete()` throwing does NOT leave the future unresolved — `failTask()` is called instead
- `onError()` throwing does NOT leave the future unresolved — `failTask()` is called in a separate try block

---

## 3. Architecture Choices

### 3.1 Why interrupt idle workers instead of polling?

**Alternative: `poll(200ms)` loop** ❌
- Burns CPU cycles when tasks are infrequent (wakes up every 200ms doing nothing)
- Wasted work when queue is empty most of the time

**Decision: `take()` + interrupt on pause** ✅
- Zero CPU cost while idle
- `executePause()` interrupts only idle threads (tracked in `idleThreads` list)
- Busy threads (mid-chunk) are never interrupted by pause — they observe the flag at the next checkpoint

### 3.2 Why no poison pill for shutdown?

The poison pill was previously used to unblock threads in `take()` on shutdown. This is now handled by `interruptIdleWorkers()` called from `shutdown()`. The `shutdown` flag is checked at the top of the worker loop and at each chunk boundary — no sentinel value needed in the queue.

### 3.3 Why single shared pool?

Better resource utilization, flexible allocation, simpler configuration versus multiple pools per task type.

### 3.4 Why thread-task binding?

Maintains task context, simple state management, better cache/connection locality versus per-chunk scheduling.

### 3.5 Why chunk-based checkpoints?

Natural pause points, no state save/restore, work never lost versus interruptible tasks.

---

## 4. System Behavior

### 4.1 Normal Operation

```
1. Client submits tasks with priorities
2. Service sorts tasks in priority queue
3. Workers pick top tasks by priority
4. Each worker binds to one task, processes chunk-by-chunk
5. At each chunk boundary: sample monitors (debounced), check pause flag
6. If not paused: continue to next chunk
7. When task completes: worker picks next task from queue
8. On completion: anti-starvation check runs on remaining queued tasks
```

### 4.2 Resource Pressure Scenario

```
Initial State:
- 2 worker threads executing 2 tasks (A and B)
- CPU: 50%, Memory: 55% (NORMAL)
- Control plane thread: sleeping (isPaused=false)

Worker 1 (Task A) completes a chunk:
├─ [CHECKPOINT]
├─ pauseIfAnyMonitorHot() (synchronized)
│  ├─ timeSinceLastSample > debounceInterval? YES → sample
│  ├─ CPU Monitor: 80% → HOT
│  ├─ shouldPause()? YES
│  ├─ executePause()
│  │  ├─ isPaused = true
│  │  ├─ pauseCount++ (global)
│  │  └─ interruptIdleWorkers() (wakes any worker blocked in take())
│  └─ Log: "PAUSING SYSTEM"
│
├─ handlePauseCheckpoint(A)
│  ├─ isPaused=true → A.incrementPauseCount() → A.pauseCount=1
│  └─ awaitResume() [BLOCKS]

Worker 2 (Task B) completes a chunk:
├─ [CHECKPOINT]
├─ pauseIfAnyMonitorHot() → sampled recently? YES → SKIP (debounced)
├─ handlePauseCheckpoint(B)
│  ├─ isPaused=true → B.incrementPauseCount() → B.pauseCount=1
│  └─ awaitResume() [BLOCKS]

[ALL WORKERS BLOCKED]

Control Plane (wakes up):
├─ isPaused=true → enter active monitoring
├─ Sample every coldMonitoringInterval (5s):
│  ├─ Sample 1: CPU 78% → HOT
│  ├─ Sample 2: CPU 48% → ALL NORMAL
│  └─ shouldResume() → YES
├─ executeResume()
│  ├─ isPaused = false
│  └─ resumeCondition.signalAll() → wake ALL workers
└─ Back to sleep

Workers resume simultaneously:
├─ Worker 1: exits awaitResume() → continues Task A
└─ Worker 2: exits awaitResume() → continues Task B
```

**Key properties:**
- **Pause detection:** Checkpoint-driven (workers sample monitors only at chunk boundaries)
- **Monitor sampling:** Synchronized + debounced (100ms debounce window) — prevents redundant sampling by multiple workers
- **Resume detection:** Control plane actively polls while paused (every 5s default)
- **Pause count:** Incremented per-task at `handlePauseCheckpoint` — ALL tasks counted fairly
- **Idle workers:** Woken immediately by interrupt when pause is triggered
- **Monitoring overhead:** Near-zero during normal execution (no continuous polling)

### 4.3 Task Killing (Excessive Pauses)

```
After awaitResume() returns, handleTerminationCondition checks:

if (taskTerminationEnabled && task.getPauseCount() > maxPauseCount) {
    TaskTerminatedException ex = new TaskTerminatedException(
        task.getTaskId(), task.getPauseCount(), maxPauseCount);

    task.onError(ex);                   // client callback
    task.failTask(ex);                  // resolves Future with exception
    executionCoordinator.recordKilledTask(task);
    tasksKilled++;

    throw new RuntimeException(ex);     // exits executeTask
}
```

Client handling:
```java
@Override
public void onError(Throwable error) {
    if (error instanceof TaskTerminatedException) {
        // Task killed due to excessive pauses — log, alert, retry
    }
}
```

---

## 5. Configuration

### 5.1 Thread Pools

The client **provides** thread pools for worker and control plane. If not provided, defaults of size 2 are created.

```java
ThrottleConfig config = ThrottleConfig.builder()
    .workerExecutorService(Executors.newFixedThreadPool(5))  // optional
    .controlPlaneExecutorService(Executors.newFixedThreadPool(2))  // optional
    .queueCapacity(100)
    .overflowPolicy(OverflowPolicy.REJECT)
    .build();
```

### 5.2 Resource Monitoring

```java
.cpuMonitor(75, 50)        // hot=75%, cold=50%
.memoryMonitor(70, 50)
.hysteresis(Duration.ofSeconds(10))
.hotMonitoringDebounceInterval(Duration.ofMillis(100))  // min interval between samples
.coldMonitoringInterval(Duration.ofSeconds(5))          // control plane poll interval
```

### 5.3 Anti-Starvation

```java
.starvationThreshold(Duration.ofHours(2))  // time before priority boost
```

### 5.4 Task Killing

```java
.maxPauseCount(5)               // kill after 5 pauses
.taskTerminationEnabled(true)   // enable/disable killing
```

---

## 6. Failure Handling

### 6.1 Task Failure

If `processChunk()` throws, the exception propagates to `executeTask()`'s catch block:
- `task.onError(e)` called (isolated — exception swallowed and logged)
- `task.failTask(e)` called (resolves Future with exception — always reached)
- `tasksFailed++`
- Worker picks next task

### 6.2 Callback Failure (`onComplete` / `onError` throws)

Both callbacks are wrapped in independent try-catches. `completeTask()` / `failTask()` always execute — the Future is always resolved.

### 6.3 Monitor Failure

If `monitor.evaluate()` throws, `MonitoringCoordinator.sampleMonitors()` catches the exception per-monitor, logs it, and continues with remaining monitors (fail-open — a broken monitor does not trigger a pause).

### 6.4 Shutdown During Pause

`shutdown()` sets the `shutdown` flag then calls `interruptIdleWorkers()`. Workers blocked in `awaitResume()` are woken by `executeResume()` (which `shutdown()` also triggers), observe `shutdown=true`, and exit cleanly.

### 6.5 Queue Overflow

| Policy | Behaviour |
|--------|-----------|
| `REJECT` | Throws `RejectedExecutionException` |
| `DISCARD_OLDEST` | Cancels oldest queued task, accepts new one |
| `BLOCK` | Caller blocks until space available |

---

## 7. Performance Characteristics

### 7.1 Idle Efficiency

Workers block in `priorityQueue.take()` with zero CPU cost. Interrupted only when a pause is triggered or shutdown is called.

### 7.2 Sampling Overhead

**Checkpoint-driven sampling ensures minimal CPU overhead:**
- Monitors are sampled ONLY at chunk boundaries (checkpoints), not continuously
- Synchronized + debounced sampling (100ms debounce window) ensures at most one sample per window, regardless of how many workers hit checkpoints simultaneously
- Expected reduction: ~75% fewer samples under high concurrency compared to per-worker sampling
- **Zero monitoring overhead** during chunk execution — CPU is 100% dedicated to task work

### 7.3 Pause/Resume Latency

- **Pause detection latency:** At most one chunk duration (worker finishes current chunk before checking)
- **Resume latency:** At most `coldMonitoringInterval` (5s default) + monitor evaluation time

---

## 8. Monitoring and Observability

### 8.1 Metrics (`ExecutorMetrics`)

| Metric | Description |
|--------|-------------|
| `activeThreads` | Threads currently executing a chunk |
| `queueSize` | Tasks waiting in queue |
| `tasksCompleted` | Total successfully completed |
| `tasksFailed` | Total failed (exception in processChunk) |
| `tasksKilled` | Total killed (exceeded maxPauseCount) |
| `pauseCount` | Number of global pause events |
| `totalPauseDuration` | Total ms spent paused |
| `isPaused` | Current pause state |

### 8.2 Logging

All monitoring decisions are logged at DEBUG level. Pause/resume transitions at INFO. Errors at ERROR.

```
DEBUG: (sampleMonitors) Monitor [cpu] - State: HOT, current=80.00%
INFO:  (executePause) PAUSING SYSTEM. Resource pressure detected.
DEBUG: (handlePauseCheckpoint) Task task-1 paused at checkpoint (pauseCount: 1)
INFO:  (executeResume) RESUMING ALL TASKS. Resources recovered.
```

---

## 9. Use Cases

The Throttle is designed for any scenario requiring resource-aware batch processing. Here are some common applications:

### 9.1 Batch Data Processing

```java
// Process large datasets in chunks with automatic resource management
List<DataRecord> records = loadLargeDataset();
DataProcessingTask task = new DataProcessingTask(records, Priority.MEDIUM, 100);
Future<Void> future = adaptiveExecutor.submit(task);

// Task pauses automatically if CPU/memory spikes during processing
// Killed automatically if paused too many times (resource exhaustion scenario)
future.get(); // wait for completion
```

### 9.2 File System Operations

```java
// Clean up old files, archive data, or process media files
List<File> filesToProcess = findFilesForCleanup();
FileCleanupTask task = new FileCleanupTask(filesToProcess, Priority.LOW, 50);
adaptiveExecutor.submit(task);

// Task yields resources to higher-priority work when system is under load
```

### 9.3 Database Batch Operations

```java
// Bulk inserts, updates, or data migration
List<DatabaseRecord> records = extractRecords();
BulkUpdateTask task = new BulkUpdateTask(records, Priority.HIGH, 500);
adaptiveExecutor.submit(task);

// Prevents database operations from overwhelming system resources
```

### 9.4 ETL (Extract, Transform, Load) Pipelines

```java
// Process data from multiple sources with different priorities
adaptiveExecutor.submit(new ExtractTask(source1), Priority.HIGH);
adaptiveExecutor.submit(new TransformTask(data), Priority.MEDIUM);
adaptiveExecutor.submit(new LoadTask(warehouse), Priority.MEDIUM);
adaptiveExecutor.submit(new ArchiveTask(oldData), Priority.LOW);

// High-priority extracts run first; LOW tasks boosted after starvation threshold
```

### 9.5 API Rate-Limited Operations

```java
// Make batch API calls that consume significant resources
List<ApiRequest> requests = buildRequestBatch();
ApiCallTask task = new ApiCallTask(requests, Priority.MEDIUM, 20);
adaptiveExecutor.submit(task);

// Automatically throttles when system resources are constrained
```

### 9.6 Image/Video Processing

```java
// Process media files (resize, transcode, thumbnail generation)
List<MediaFile> files = getMediaQueue();
MediaProcessingTask task = new MediaProcessingTask(files, Priority.LOW, 10);
adaptiveExecutor.submit(task);

// CPU-intensive operations pause when system needs resources for other work
```

### 9.7 Report Generation

```java
// Generate multiple reports with different urgency levels
adaptiveExecutor.submit(new DailyReportTask(data), Priority.HIGH);
adaptiveExecutor.submit(new WeeklyReportTask(data), Priority.MEDIUM);
adaptiveExecutor.submit(new MonthlyReportTask(data), Priority.LOW);

// Urgent reports complete first, others process when resources available
```

### 9.8 Search Index Building

```java
// Build or rebuild search indexes incrementally
List<Document> documents = getDocumentsForIndexing();
IndexBuildTask task = new IndexBuildTask(documents, Priority.LOW, 1000);
adaptiveExecutor.submit(task);

// Index building yields to real-time queries when system is busy
```

---

## 10. Future Enhancements

### Intelligent Features (Lightweight, Minimal Overhead)

See `INTELLIGENT_FEATURES_PROPOSAL_V2.md` for detailed design and implementation plans.

**Design Principle**: All enhancements must maintain the core philosophy that **the executor itself should not consume significant system resources**.

#### Priority 1: Low-Hanging Fruit (Recommended)
**Total overhead**: ~250KB memory, <10ms CPU per task (~0.1-1% overhead)
**Implementation time**: 1-2 weeks
**Dependencies**: None (pure Java)

1. **Per-Task Metrics Collection**
   - Track execution details: queue wait, execution time, pause count, efficiency
   - Bounded ring buffer (1000 tasks = 200KB memory)
   - O(1) insertions, zero I/O
   - Enables deep debugging without external tools

2. **Health Scoring**
   - At-a-glance system health (0-100 score with grade)
   - Pure computation on existing metrics (<1ms)
   - No persistent state, called on-demand only

3. **Statistical Anomaly Detection**
   - Z-score based outlier detection (no ML required)
   - Welford's online algorithm (8KB memory, O(1) updates)
   - Catches 90% of issues: execution time anomalies, excessive pauses, low efficiency

4. **Workload Profiling**
   - Learn task characteristics per task type (1KB per type)
   - Recommend optimal chunk sizes automatically
   - Online statistics (no raw data storage)

**Key Implementation Principles**:
- ✅ Bounded memory (ring buffers, aggregates only)
- ✅ Efficient algorithms (O(1) or O(log n))
- ✅ In-memory only, no disk I/O
- ✅ Feature flags to disable entirely

#### What NOT to Implement (High Overhead)
These features should be avoided due to significant resource consumption:

- ❌ **Time-series databases**: Network I/O, storage costs, query latency (10-100ms)
- ❌ **Machine learning models**: 10-100MB memory, 10-100ms inference latency
- ❌ **Distributed tracing**: Network overhead, span storage, connection management
- ❌ **Real-time dashboards**: WebSocket management, continuous push overhead
- ❌ **Reinforcement learning**: Complex, high memory, research only

#### Other Future Enhancements (Infrastructure)
- Dynamic pool sizing (auto-scale based on load)
- Task dependencies (DAG-based execution)
- Distributed execution (cluster-aware scheduling)
- Advanced draining (execution-time or failure-rate based)

---

## 11. Conclusion

The **Throttle Service** provides priority-based, resource-aware, chunk-driven task execution with clean pause/resume semantics.

**Key Strengths:**
- ✅ Zero CPU idle cost (`take()` + interrupt-based wakeup)
- ✅ Chunk-driven pause detection (no continuous polling)
- ✅ Control-plane resume (workers never busy-wait)
- ✅ Fair pause counting for all tasks
- ✅ Callback safety (`onComplete`/`onError` exceptions cannot leave futures unresolved)
- ✅ Monitor fault tolerance (broken monitor does not crash the executor)
- ✅ Client-provided or default thread pools
- ✅ Fully configurable (all thresholds, intervals, policies are client-tunable)
- ✅ Minimal resource footprint (executor itself has negligible overhead)

**Planned Enhancements:**
- ✅ Lightweight intelligence features with <1% overhead (per-task metrics, health scoring, anomaly detection, workload profiling)
- ✅ Zero external dependencies for core enhancements
- ✅ Can be completely disabled via feature flags
