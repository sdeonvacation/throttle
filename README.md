<h1 align="center">Throttle</h1>

<h3 align="center">Your background jobs are killing your app. Fix it in 5 minutes.</h3>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.sdeonvacation/throttle"><img src="https://img.shields.io/maven-central/v/io.github.sdeonvacation/throttle?label=Maven%20Central&color=brightgreen" alt="Maven Central"></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange.svg" alt="Java 17+">
  <a href="https://github.com/sdeonvacation/throttle/blob/master/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="Apache License 2.0"></a>
  <a href="https://javadoc.io/doc/io.github.sdeonvacation/throttle"><img src="https://javadoc.io/badge2/io.github.sdeonvacation/throttle/javadoc.svg" alt="Javadoc"></a>
</p>

<p align="center">
  <a href="#the-problem">The Problem</a> •
  <a href="#the-fix">The Fix</a> •
  <a href="#install">Install</a> •
  <a href="#how-it-works">How It Works</a> •
  <a href="#configuration">Configuration</a> •
  <a href="#faq">FAQ</a>
</p>

---

## The Problem

You have background jobs — reports, ETL, batch processing, file uploads. They run fine... until they don't.

```java
@Service
public class OrderService {
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(4);

    // Business logic — must stay responsive
    public Order createOrder(OrderRequest request) {
        return orderRepository.save(new Order(request));
    }

    // Background job — processes thousands of records
    public void generateDailyReport(List<Order> orders) {
        backgroundExecutor.submit(() -> {
            for (Order order : orders) {
                reportService.process(order);  // 💀 Competes with createOrder() for CPU/memory
            }
        });
    }
}
```

Then this happens:

- **Monday 2pm**: Marketing kicks off their weekly report
- **Monday 2:01pm**: CPU hits 95%, `createOrder()` latency spikes from 50ms to 3 seconds
- **Monday 2:02pm**: Your Kubernetes pod gets OOMKilled
- **Monday 2:03pm**: PagerDuty goes off, you stop what you're doing

**The root cause**: Your background jobs don't know when to back off. They'll happily consume 100% of resources while your users wait.

## The Fix

Throttle is a drop-in replacement for `ExecutorService` that automatically pauses tasks when your system is under pressure.

```java
// Before: Background jobs compete with your app for resources
ExecutorService executor = Executors.newFixedThreadPool(4);

// After: Background jobs yield when CPU/memory is high
ThrottleService executor = ThrottleServiceFactory.builder()
    .cpuMonitor(75, 50)     // Pause when CPU > 75%, resume when < 50%
    .memoryMonitor(70, 50)  // Pause when memory > 70%, resume when < 50%
    .build();
```

That's it. Your background jobs now automatically pause during traffic spikes and resume when things calm down.

## Install

```xml
<dependency>
    <groupId>io.github.sdeonvacation</groupId>
    <artifactId>throttle</artifactId>
    <version>1.0.2</version>
</dependency>
```

Zero dependencies. Just add and go.

## 30-Second Example

```java
// 1. Create the executor (once, typically at startup)
ThrottleService executor = ThrottleServiceFactory.builder()
    .cpuMonitor(75, 50)
    .memoryMonitor(70, 50)
    .build();

// 2. Submit chunked tasks (the chunks are pause points)
executor.submit(new AbstractChunkableTask<Order>(orders, Priority.LOW, 100) {
    @Override
    public void processChunk(List<Order> chunk) {
        chunk.forEach(this::processOrder);
        // ↑ After each chunk, Throttle checks CPU/memory
        // If system is stressed, it pauses here until resources free up
    }
});

// Or with ChunkProcessor — no subclassing required:
ChunkProcessor<Order> processor = chunk -> chunk.forEach(this::processOrder);
executor.submit(new DelegatingChunkableTask<>("daily-report", orders, Priority.LOW, 100, processor));

// 3. Your app stays responsive. Users are happy. You sleep through the night.
```

## Two Ways to Define Tasks

### Option 1: Extend AbstractChunkableTask

Full control — override lifecycle callbacks, add fields, track state.

```java
public class OrderReportTask extends AbstractChunkableTask<Order> {
    public OrderReportTask(List<Order> orders) {
        super("order-report", orders, Priority.LOW, 100);
    }

    @Override
    public void processChunk(List<Order> chunk) {
        chunk.forEach(this::processOrder);
    }

    @Override
    public void onComplete() {
        log.info("Report generation finished");
    }
}
```

### Option 2: Implement ChunkProcessor (no subclassing)

Lightweight — implement a strategy interface and wrap it. Best for simple tasks or when you want to keep business logic free of framework coupling.

```java
// Lambda for simple cases
ChunkProcessor<Order> processor = chunk -> chunk.forEach(this::processOrder);
executor.submit(new DelegatingChunkableTask<>("order-report", orders, Priority.LOW, 100, processor));

// Anonymous class for lifecycle hooks
ChunkProcessor<Order> processor = new ChunkProcessor<>() {
    @Override
    public void processChunk(List<Order> chunk) {
        chunk.forEach(this::processOrder);
    }

    @Override
    public void onComplete(String taskId) {
        log.info("Task {} finished", taskId);
    }

    @Override
    public void onError(String taskId, Throwable error) {
        log.error("Task {} failed: {}", taskId, error.getMessage());
    }
};

executor.submit(new DelegatingChunkableTask<>("order-report", orders, Priority.LOW, 100, processor));
```

Both approaches are fully supported and interchangeable.

## How It Works

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              YOUR APPLICATION                                │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   API Requests (business logic)     Background Jobs (reports, ETL, etc.)     │
│            │                                      │                          │
│            ▼                                      ▼                          │
│   ┌─────────────────┐                  ┌─────────────────────┐               │
│   │  Normal threads │                  │   ThrottleService   │               │
│   │  (unaffected)   │                  └─────────────────────┘               │
│   └─────────────────┘                             │                          │
│                                                   ▼                          │
│                                    ┌──────────────────────────┐              │
│                                    │   Task split into chunks │              │
│                                    │   [1] [2] [3] [4] [5]... │              │
│                                    └──────────────────────────┘              │
│                                                   │                          │
│                              ┌────────────────────┼────────────────────┐     │
│                              ▼                    ▼                    ▼     │
│                         ┌────────┐           ┌────────┐           ┌────────┐ │
│                         │Chunk 1 │──────────▶│Chunk 2 │──────────▶│Chunk 3 │ │
│                         └────────┘           └────────┘           └────────┘ │
│                              │                    │                    │     │
│                              ▼                    ▼                    ▼     │
│                        ┌──────────┐         ┌──────────┐         ┌──────────┐│
│                        │ Check    │         │ Check    │         │ Check    ││
│                        │ CPU/Mem  │         │ CPU/Mem  │         │ CPU/Mem  ││
│                        └──────────┘         └──────────┘         └──────────┘│
│                              │                    │                    │     │
│                         OK? ─┴─ HOT?         OK? ─┴─ HOT?              │     │
│                          │      │             │      │                 │     │
│                          ▼      ▼             ▼      ▼                 ▼     │
│                       Continue  PAUSE ──▶  Wait for  ──▶           Continue  │
│                                            resources                         │
│                                            to cool                           │
└──────────────────────────────────────────────────────────────────────────────┘

         SYSTEM LOAD                          TASK BEHAVIOR
    ┌───────────────────┐              ┌───────────────────────┐
    │ ████████░░ 80%    │   ─────▶     │ ⏸  PAUSED (waiting)   │
    │ CPU/memory high!  │              │                       │
    └───────────────────┘              └───────────────────────┘

    ┌───────────────────┐              ┌───────────────────────┐
    │ ████░░░░░░ 40%    │   ─────▶     │ ▶  RUNNING (resumed)  │
    │ CPU/memory normal │              │                       │
    └───────────────────┘              └───────────────────────┘
```

1. **You split work into chunks** — Throttle needs natural pause points
2. **Throttle executes chunks** — Just like a normal executor
3. **Between chunks, Throttle checks resources** — Is CPU hot? Is memory tight?
4. **If resources are constrained, Throttle pauses** — Your task waits (not spinning, actually blocked)
5. **When resources free up, Throttle resumes** — Right where it left off

**Why chunks?** Java can't pause a thread mid-execution. Chunks give Throttle safe points to pause without losing work.

### The Before/After

| Without Throttle | With Throttle |
|------------------|---------------|
| Background job runs at full speed | Background job pauses when system is stressed |
| API latency spikes during batch jobs | API stays responsive |
| OOMKilled pods | Graceful degradation |
| PagerDuty at 2am | Sleep |

## When to Use Throttle

**Perfect for:**
- ETL pipelines and batch processing
- Report generation
- Bulk API calls (rate-limited external services)
- File processing (uploads, exports, archives)
- Database migrations and cleanup jobs
- Any "background work" that can be chunked

**Not for:**
- Sub-millisecond latency requirements (chunk overhead is ~1ms)
- Work that can't be split into chunks
- Simple fire-and-forget tasks with no resource concerns

## Configuration

Every parameter is tunable. Sane defaults, full control when you need it.

```java
ThrottleService executor = ThrottleServiceFactory.builder()
    // Resource thresholds
    .cpuMonitor(75, 50)              // hot=75%, cold=50%
    .memoryMonitor(70, 50)           // hot=70%, cold=50%

    // Threading (bring your own or use defaults)
    .workerThreadPool(Executors.newFixedThreadPool(10))
    .monitoringThreadPool(Executors.newFixedThreadPool(2))

    // Timing
    .hysteresis(Duration.ofSeconds(10))           // Min time in state before transition
    .coldMonitoringInterval(Duration.ofSeconds(5)) // Resume detection polling interval
    .hotMonitoringDebounceInterval(Duration.ofMillis(100)) // Debounce between checkpoint samples

    // Queue management
    .queueCapacity(100)
    .overflowPolicy(OverflowPolicy.REJECT)  // or DISCARD_OLDEST, BLOCK

    // Task killing (opt-in, for runaway tasks)
    .maxPauseCount(5)                // Kill a task after 5 pauses
    .taskTerminationEnabled(true)    // Enabled by default

    // Anti-starvation
    .starvationThreshold(Duration.ofHours(2))  // Boost priority after 2h

    .build();
```

### Priority Scheduling

```java
// High priority tasks run first
executor.submit(new MyTask(items, Priority.HIGH, 100));

// Low priority tasks yield to high priority
executor.submit(new MyTask(items, Priority.LOW, 100));
```

### Monitoring

```java
ExecutorMetrics metrics = executor.getMetrics();

metrics.getActiveThreads();     // Currently running
metrics.getQueueSize();         // Waiting in queue
metrics.getTasksCompleted();    // Finished successfully
metrics.getTasksFailed();       // Failed with exception
metrics.getTasksKilled();       // Killed for pausing too much
metrics.isPaused();             // Is system currently paused?
metrics.getPauseCount();        // Total pauses since startup
```

Plug these into Prometheus, Datadog, or whatever you use.

## Comparison

| Feature | Throttle | ExecutorService | Resilience4j Bulkhead | Spring Batch |
|---------|----------|-----------------|----------------------|--------------|
| Auto-pauses on CPU pressure | ✅ | ❌ | ❌ | ❌ |
| Auto-pauses on memory pressure | ✅ | ❌ | ❌ | ❌ |
| Auto-resumes when clear | ✅ | ❌ | ❌ | ❌ |
| Priority scheduling | ✅ | ❌ | ❌ | Limited |
| Zero dependencies | ✅ | ✅ | ❌ | ❌ |
| Chunked checkpoints | ✅ | ❌ | ❌ | ✅ |

## FAQ

<details>
<summary><b>Why do I need to split work into chunks?</b></summary>

Java doesn't let you pause a thread mid-execution. Chunks give Throttle safe points to pause without losing progress. Think of them as checkpoints in a video game.
</details>

<details>
<summary><b>What's the overhead?</b></summary>

Minimal. Throttle only checks resources between chunks (not continuously). The check itself is ~1ms. If you're processing items in chunks of 100, that's 0.01ms per item.
</details>

<details>
<summary><b>My pods auto-scale. Why do I need this?</b></summary>

Auto-scaling and Throttle solve different problems:

| | Auto-scaling | Throttle |
|---|---|---|
| **What it does** | Adds more instances | Prioritizes work within each instance |
| **Reaction time** | Minutes (spin up new pods) | Milliseconds (pause between chunks) |
| **Solves** | "Not enough capacity" | "Background jobs starving business logic" |
| **Cost** | More $$ (more instances) | Zero (just smarter scheduling) |

**The gap auto-scaling can't fill:** When your pod is at 80% CPU, auto-scaling might spin up another pod. But *within* that pod, your batch job is still competing with API requests. Throttle makes the batch job yield.

**Use both:** Auto-scaling for capacity. Throttle for priority.
</details>

<details>
<summary><b>What about environments without auto-scaling?</b></summary>

Throttle becomes critical. On Cloud Foundry, on-prem, or fixed-capacity environments, you can't add instances when load spikes. Throttle is your safety valve — background work automatically backs off before you hit OOM.
</details>

<details>
<summary><b>What happens to paused tasks during shutdown?</b></summary>

`executor.shutdown()` wakes all paused tasks and lets them complete. `shutdownNow()` interrupts them.
</details>

<details>
<summary><b>Can I use this with Spring Boot?</b></summary>

Yes. Create a `@Bean` and inject it:

```java
@Bean
public ThrottleService throttleService() {
    return ThrottleServiceFactory.builder()
        .cpuMonitor(75, 50)
        .memoryMonitor(70, 50)
        .build();
}
```
</details>

<details>
<summary><b>What if my task keeps getting paused?</b></summary>

Two mechanisms handle this:

1. **Priority boosting** — Tasks waiting too long (default: 2 hours) get their priority bumped (LOW → MEDIUM → HIGH). This prevents starvation.

2. **Task termination** (optional) — If `taskTerminationEnabled(true)` is set, tasks that pause more than `maxPauseCount` times (default 5) are killed with `TaskTerminatedException`. Disabled by default.
</details>

## Try the Simulator

Want to see Throttle in action? The simulator lets you watch real-time pause/resume behavior:

```bash
git clone https://github.com/sdeonvacation/throttle.git
cd throttle/simulator
mvn spring-boot:run
# Open http://localhost:8080/api/simulator/dashboard
```

<p align="center">
  <img src="docs/images/throttle-demo.gif" alt="Throttle Simulator Demo" width="700">
</p>

## Full Documentation

- **[DESIGN.md](DESIGN.md)** — Architecture deep-dive with diagrams
- **[Javadoc](https://javadoc.io/doc/io.github.sdeonvacation/throttle)** — API reference
- **[Simulator docs](simulator/docs/README.md)** — Test scenarios and edge cases

## Contributing

PRs welcome! See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache 2.0](LICENSE)

---
