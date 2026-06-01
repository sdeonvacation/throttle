# Throttle Simulator

A Spring Boot application for testing and demonstrating the **Throttle Service** with real CPU and memory load.

## Overview

The simulator provides end-to-end testing of the Throttle Service by:
- Generating actual CPU and memory load via configurable load generators
- Creating realistic task workloads with `SimulatedTask` and `FailingSimulatedTask`
- Testing pause/resume behaviour under real resource pressure
- Validating task killing, priority scheduling, queue overflow, and fault tolerance
- Running 13 scenarios — 7 positive, 5 negative/edge-case, 1 API pattern demo

## Starting the Application

```bash
cd simulator
mvn spring-boot:run
```

The application starts on **http://localhost:8080**.  
Open the dashboard at **http://localhost:8080/api/simulator/dashboard**.

---

## Features

### Real-Time Dashboard
- **Glassmorphism CSS theme** with CSS variable design system
- **Live updates** via WebSocket with RAF-batched coalescing (500ms refresh)
- **Animated SVG gauges** for CPU/Memory with stroke-dashoffset animation
- **Sparkline charts** (Chart.js) with 60-point history trending
- **SVG executor flow visualization** with animated queue→threads→output dots
- **Particle system canvas** background with floating effects
- **Material-style ripple effects** on buttons
- **Responsive grid layout** (12 columns, 2 breakpoints) with staggered card animations
- **System resources**: CPU and memory gauges, executor status (queue, threads, pause)
- **Task metrics**: completed, failed, killed, paused counts

### Independent Load Control
- **CPU Load Generator**: adjustable target (10–100%), configurable duration, start/stop independently
- **Memory Load Generator**: adjustable target (10–90% of heap), configurable duration, automatic release

### 13 Test Scenarios
- One-click execution from the dashboard
- Results shown in real time with activity log

---

## Test Scenarios

### Positive Scenarios

| # | Name | Endpoint | Purpose |
|---|------|----------|---------|
| 1 | Normal Operation | `normal-operation` | Baseline throughput, no resource pressure (2 workers) |
| 2 | Resource Spike | `resource-spike` | CPU spikes to 80% for 10s, pause/resume during load (2 workers) |
| 3 | Sustained Load | `sustained-load` | Continuous 60% CPU load for 20s with 20 tasks (2 workers) |
| 4 | Memory Pressure | `memory-pressure` | Memory allocation to 70% for 12s with 25 tasks (2 workers) |
| 5 | Task Killing | `task-killing` | Tasks killed after exceeding `maxPauseCount=2` via 4 memory spikes |
| 6 | Priority Scheduling | `priority-scheduling` | HIGH priority tasks complete before LOW |
| 7 | Stress Test | `stress-test` | 100 tasks, 8 workers, mixed priorities |

### Negative / Edge-Case Scenarios

| # | Name | Endpoint | What it tests |
|---|------|----------|---------------|
| 8 | Flapping Monitor | `flapping-monitor` | Rapid HOT/NORMAL oscillation — debounce absorbs thrashing, all tasks complete, none killed |
| 9 | Queue Overflow | `queue-overflow` | Submits 10 tasks to a 3-slot queue — `RejectedExecutionException` fires, executor recovers |
| 10 | Failing Tasks | `failing-tasks` | Half of tasks throw in `processChunk()` — workers survive, healthy tasks complete, metrics accurate |
| 11 | Cascade Kill | `cascade-kill` | All 3 running tasks killed by 3 CPU spikes (`maxPauseCount=2`) — executor recovers with fresh tasks |
| 12 | Shutdown Under Load | `shutdown-under-load` | `shutdown()` called with 15 tasks in flight — `awaitTermination(10s)` returns without deadlock |
| 13 | DelegatingChunkableTask | `delegating-chunkabletask` / `delegating` | 20 tasks using `DelegatingChunkableTask` + `ChunkProcessor` pattern; verifies all `onComplete` callbacks fire |

---

## Running Tests

### Via Shell Script

```bash
cd simulator

# Run all 13 scenarios sequentially
./run-tests.sh

# Run a specific scenario by number or alias
./run-tests.sh 1          # Normal Operation
./run-tests.sh normal
./run-tests.sh 2          # Resource Spike
./run-tests.sh spike
./run-tests.sh 5          # Task Killing
./run-tests.sh killing
./run-tests.sh 8          # Flapping Monitor
./run-tests.sh flapping
./run-tests.sh 9          # Queue Overflow
./run-tests.sh overflow
./run-tests.sh 10         # Failing Tasks
./run-tests.sh failing
./run-tests.sh 11         # Cascade Kill
./run-tests.sh cascade
./run-tests.sh 12         # Shutdown Under Load
./run-tests.sh shutdown
./run-tests.sh 13         # DelegatingChunkableTask
./run-tests.sh delegating

# Run all tests and get full JSON results
./run-tests.sh all
```

### Via curl (app must be running)

```bash
# Individual scenarios
curl -X POST http://localhost:8080/api/simulator/run/normal-operation
curl -X POST http://localhost:8080/api/simulator/run/resource-spike
curl -X POST http://localhost:8080/api/simulator/run/sustained-load
curl -X POST http://localhost:8080/api/simulator/run/memory-pressure
curl -X POST http://localhost:8080/api/simulator/run/task-killing
curl -X POST http://localhost:8080/api/simulator/run/priority-scheduling
curl -X POST http://localhost:8080/api/simulator/run/stress-test
curl -X POST http://localhost:8080/api/simulator/run/flapping-monitor
curl -X POST http://localhost:8080/api/simulator/run/queue-overflow
curl -X POST http://localhost:8080/api/simulator/run/failing-tasks
curl -X POST http://localhost:8080/api/simulator/run/cascade-kill
curl -X POST http://localhost:8080/api/simulator/run/shutdown-under-load
curl -X POST http://localhost:8080/api/simulator/run/delegating-chunkabletask

# Run all tests
curl -X POST http://localhost:8080/api/simulator/run-all
```

### Load Control

```bash
# CPU load
./run-tests.sh cpu-start 80 10000     # 80% CPU for 10s
./run-tests.sh cpu-stop

# Memory load
./run-tests.sh memory-start 70 8000   # 70% heap for 8s
./run-tests.sh memory-stop

# Or via API
curl -X POST "http://localhost:8080/api/simulator/load/cpu/start?targetPercent=80&durationMs=10000"
curl -X POST "http://localhost:8080/api/simulator/load/cpu/stop"
curl -X POST "http://localhost:8080/api/simulator/load/memory/start?targetPercent=70&durationMs=8000"
curl -X POST "http://localhost:8080/api/simulator/load/memory/stop"
```

### Other Endpoints

```bash
# Dashboard (browser)
open http://localhost:8080/api/simulator/dashboard

# Executor + load status
curl http://localhost:8080/api/simulator/status
```

---

## Scenario Details

### 1. Normal Operation
- **Config**: 2 workers, `hysteresis(1s)`, `hotMonitoringDebounceInterval(500ms)`, `coldMonitoringInterval(3s)`
- **Tasks**: 50 tasks (10 HIGH, 20 MEDIUM, 20 LOW), 100 items/task, chunk size 20
- **Expected**: All 50 tasks complete, `pauseCount = 0`

### 2. Resource Spike
- **Config**: 2 workers, thresholds: cpu(70,45) memory(65,45), `hysteresis(1s)`, `hotMonitoringDebounceInterval(500ms)`, `coldMonitoringInterval(2s)`
- **Tasks**: 30 tasks (300 items/task, chunk 15)
- **Load**: CPU spike to 80% for 10s after 1s warmup
- **Expected**: Tasks complete, `pauseCount > 0`

### 3. Sustained Load
- **Config**: 2 workers, `hysteresis(1s)`, `hotMonitoringDebounceInterval(500ms)`
- **Tasks**: 20 tasks (150 items/task, chunk 20) submitted during sustained load
- **Load**: Continuous 60% CPU for 20s
- **Expected**: Tasks complete with multiple pauses

### 4. Memory Pressure
- **Config**: 2 workers, thresholds: cpu(80,50) memory(65,45), `hysteresis(1s)`, `hotMonitoringDebounceInterval(500ms)`, `coldMonitoringInterval(2s)`
- **Tasks**: 25 tasks (100 items/task, chunk 20)
- **Load**: Heap allocated to 70% for 12s after 2s warmup
- **Expected**: Tasks pause during memory pressure, resume after release

### 5. Task Killing
- **Config**: 3 workers, thresholds cpu(70,40) memory(60,40), `hysteresis(1s)`, `maxPauseCount=2`
- **Tasks**: 5 very long tasks (1000 items, chunk 5), pool size 3
- **Load**: 4 memory spikes (95% heap for 8s, 5s cooldown between)
- **Expected**: At least 1 task killed (`TaskTerminatedException`); `tasksKilled > 0`

### 6. Priority Scheduling
- **Tasks**: 2 HIGH tasks + 10 LOW tasks, `queueCapacity=2`
- **Expected**: HIGH tasks complete before LOW tasks leave the queue

### 7. Stress Test
- **Tasks**: 100 tasks (mixed priorities), 8 workers, `queueCapacity=200`
- **Expected**: 90%+ tasks complete

### 8. Flapping Monitor *(edge case)*
- **Tasks**: 12 long tasks, pool size 4, `maxPauseCount=20`, termination disabled
- **Load**: 5 × (65% CPU for 1s ON / 1s OFF) — tight hot threshold (55%)
- **Expected**: All 12 tasks complete, none killed; `pauseCount ≤ 10` (debounce absorbs flapping)

### 9. Queue Overflow *(edge case)*
- **Setup**: 1 worker, `queueCapacity=3`, REJECT overflow policy
- **Submission**: 10 tasks fired instantly
- **Expected**: At least 6 tasks rejected (`RejectedExecutionException`); recovery task accepted after drain

### 10. Failing Tasks *(edge case)*
- **Tasks**: 20 tasks total — 10 healthy, 10 `FailingSimulatedTask` (throw on first chunk)
- **Expected**: 10 completed, 10 failed; `tasksFailed = 10`; all 3 workers still operational

### 11. Cascade Kill *(edge case)*
- **Tasks**: 3 very long tasks (500 items, `maxPauseCount=2`), then 5 recovery tasks
- **Load**: 3 × 70% CPU spikes for 3s (kills all 3 initial tasks)
- **Expected**: All 3 initial tasks killed; all 5 recovery tasks complete (executor healthy)

### 12. Shutdown Under Load *(edge case)*
- **Tasks**: 15 tasks submitted, then `shutdown()` called after 300ms
- **Expected**: `awaitTermination(10s)` returns `true`; no deadlock; `isShutdown() = true`

### 13. DelegatingChunkableTask *(API pattern)*
- **Config**: 4 workers, no resource pressure, thresholds cpu(90,70) memory(90,70), `taskTerminationEnabled=false`
- **Tasks**: 20 tasks via `DelegatingSimulatedTask` wrapper (50 items/task, chunk 10)
- **Purpose**: Verify the `DelegatingChunkableTask` + `ChunkProcessor` delegation pattern works identically to direct `AbstractChunkableTask` extension
- **Expected**: All 20 tasks complete, all `onComplete` callbacks fire, `tasksCompleted = 20`, `tasksFailed = 0`, `tasksKilled = 0`

---

## Response Format

Each scenario returns a JSON response:

```json
{
  "success": true,
  "duration": 12500,
  "tasksSubmitted": 50,
  "tasksCompleted": 50,
  "tasksFailed": 0,
  "tasksKilled": 0,
  "pauseCount": 0,
  "error": ""
}
```

The `run-all` endpoint returns a summary:

```json
{
  "totalTests": 13,
  "passed": 13,
  "results": [ ... ]
}
```

---

## Integration with Throttle

The simulator uses the Throttle library directly. Each scenario creates a fresh executor via the factory:

```java
ThrottleService executor = ThrottleServiceFactory.builder()
    .workerThreadPool(Executors.newFixedThreadPool(2))  // client provides pool (default: 2)
    .queueCapacity(100)
    .cpuMonitor(75, 50)          // hot=75%, cold=50%
    .memoryMonitor(70, 50)
    .hysteresis(Duration.ofSeconds(1))
    .coldMonitoringInterval(Duration.ofSeconds(3))
    .hotMonitoringDebounceInterval(Duration.ofMillis(500))
    .maxPauseCount(5)
    .taskTerminationEnabled(true)
    .build();
```

### Task Submission Patterns

**Direct Extension (scenarios 1–12):**
```java
SimulatedTask task = new SimulatedTask("id", itemCount, priority, chunkSize, delayMs);
executor.submit(task);  // extends AbstractChunkableTask
```

**Delegation Pattern (scenario 13):**
```java
DelegatingSimulatedTask wrapper = new DelegatingSimulatedTask("id", itemCount, priority, chunkSize, delayMs);
executor.submit(wrapper.getTask());  // wraps AbstractChunkableTask via DelegatingChunkableTask + ChunkProcessor
```

Key points:
- **No `poolSize` config** — the client provides an `ExecutorService` (or omits it for default pool of size 2)
- **Each scenario creates its own executor** — full isolation between tests
- **`TaskTerminatedException`** is thrown when `pauseCount > maxPauseCount` — clients handle it in `onError()`
- **Delegation pattern** (`DelegatingChunkableTask` + `ChunkProcessor`) is equivalent to direct extension

---

## Architecture

```
simulator/
├── src/main/java/.../simulator/
│   ├── SimulatorApplication.java       # Spring Boot entry point
│   ├── config/                         # Spring beans (executor, monitors)
│   ├── controller/
│   │   └── SimulatorController.java    # REST + dashboard endpoints
│   ├── load/
│   │   ├── CpuLoadGenerator.java       # Burns CPU cycles at configurable intensity
│   │   └── MemoryLoadGenerator.java    # Allocates heap at configurable size
│   ├── monitor/
│   │   ├── SystemMonitor.java          # Samples CPU/memory via JMX
│   │   ├── SystemSnapshot.java         # Point-in-time snapshot
│   │   └── MonitoringStats.java        # Min/max/avg statistics
│   ├── service/
│   │   ├── MonitoringService.java      # Pushes metrics to dashboard via WebSocket
│   │   ├── LoadControlService.java     # Tracks CPU/memory load active state
│   │   └── BackgroundLoadService.java  # Baseline JVM load generator for realistic dashboard demos
│   ├── tasks/
│   │   ├── SimulatedTask.java          # Normal task (sleeps per item to simulate work)
│   │   ├── FailingSimulatedTask.java   # Task that throws on first chunk (scenario 10)
│   │   └── DelegatingSimulatedTask.java # Demonstrates DelegatingChunkableTask + ChunkProcessor pattern (scenario 13)
│   └── test/
│       ├── ScenarioRunner.java         # 13 scenario implementations
│       └── TestResult.java             # Scenario result DTO (includes tasksSubmitted)
└── src/main/resources/
    ├── templates/dashboard.html        # Glassmorphic dashboard with SVG gauges, sparklines, particle canvas
    └── application.properties
```

---

## Troubleshooting

### High CPU usage during tests
Expected — CPU load generators intentionally burn cycles. Usage returns to normal after tests complete.

### Out of memory during memory pressure test
Increase heap:
```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx4g"
```

### Task killing test shows `tasksKilled = 0`
Ensure tasks are long enough to still be running when spikes fire.  
Check `pauseCount` — if it is 0, the spikes are not crossing the hot threshold.  
Try lowering `cpuMonitor(70, 40)` threshold or increasing spike intensity.

### Scenario times out (HTTP 200 with `"message": "Test started and running in background"`)
The test is still running. Scenarios are given up to 120s (run-all: 300s) before returning a background response. Check application logs for progress.

### Dashboard shows 404
Ensure you are using the correct URL: `http://localhost:8080/api/simulator/dashboard`  
The controller is mapped under `/api/simulator`.

---

## System Requirements

- **Java**: 17+
- **Maven**: 3.6+
- **OS**: macOS / Linux
