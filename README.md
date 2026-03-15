# Throttle

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.0.0-brightgreen.svg)](https://search.maven.org/artifact/io.github.throttle/throttle)

A Java library for adaptive task execution with resource-aware monitoring and automatic pause/resume capabilities.

## Overview

Throttle is a sophisticated task execution framework that automatically adapts to system resource availability. It monitors CPU and memory usage and intelligently pauses/resumes task execution to prevent system overload, making it ideal for resource-intensive batch processing applications.

**Offload Heavy Background Tasks**: Submit any application's heavy background tasks (data processing, file operations, batch jobs, report generation) to Throttle, allowing your application to focus on running actual business logic while Throttle manages resource-intensive operations intelligently.

## Key Features

- **Automatic Resource Monitoring**: Continuously monitors CPU and memory usage
- **Adaptive Pause/Resume**: Automatically pauses task execution when resources are constrained and resumes when resources become available
- **Chunked Task Execution**: Tasks are split into chunks that serve as checkpoints for pausing/resuming. This is essential because there is no way to natively pause a running task/thread and resume it at the same point without the support of the task itself - tasks must have checkpoints where they can be paused
- **Priority-Based Scheduling**: Supports HIGH, MEDIUM, and LOW priority tasks
- **Task Termination**: Configurable task killing for tasks that pause too frequently
- **Configurable Thresholds**: Customizable hot/cold thresholds for CPU and memory
- **Hysteresis Support**: Prevents rapid oscillation between pause/resume states
- **Queue Management**: Bounded queues with configurable overflow policies
- **Comprehensive Monitoring**: Built-in metrics for task completion, failures, and resource usage
- **Full Client Control**: You control everything - thread pools, thresholds, intervals, and policies. Bring your own ExecutorService or use defaults.

## Why Use Throttle?

Modern applications often need to run heavy background tasks alongside their core business logic:
- **Data processing** - ETL pipelines, batch transformations, analytics
- **File operations** - Bulk uploads, media processing, archive generation
- **Database operations** - Bulk updates, migrations, cleanup jobs
- **Report generation** - Scheduled reports, data exports, PDF generation
- **API integrations** - Rate-limited batch API calls, webhook processing

**The Problem**: Running these tasks directly in your application can:
- Starve resources from critical business logic
- Cause performance degradation during high-load periods
- Lead to unpredictable response times and system instability

**The Solution**: Submit heavy background tasks to Throttle and let it handle resource management automatically. Your application stays responsive and focused on business logic while Throttle:
- Monitors system resources (CPU/memory) continuously
- Pauses background tasks when resources are constrained
- Resumes tasks automatically when resources become available
- Prioritizes critical tasks over routine maintenance jobs
- Terminates problematic tasks that consume excessive resources

## Complete Client-Side Control

**You control everything** - Throttle provides intelligent resource management without dictating how your application should run:

### 🎛️ Control Your Thread Pools
- **Worker threads**: Provide your own `ExecutorService` for task execution or use the default (2 threads)
- **Control plane threads**: Provide your own `ExecutorService` for monitoring/coordination or use the default (2 threads)
- **Example**: `Executors.newFixedThreadPool(10)`, `Executors.newCachedThreadPool()`, or any custom implementation

### 🔧 Configure All Parameters
Every aspect is configurable:
- **CPU/Memory thresholds**: Set hot/cold percentages for pause/resume decisions
- **Monitoring intervals**: Control how often resources are checked
- **Queue capacity**: Define maximum pending tasks
- **Overflow policies**: Choose REJECT, DISCARD_OLDEST, or BLOCK behavior
- **Task termination**: Set max pause count before killing tasks
- **Anti-starvation**: Configure priority boosting thresholds
- **Hysteresis**: Prevent rapid state oscillations

### 📊 Full Observability
Access detailed metrics at any time:
- Active tasks, queue size, completed/failed/killed counts
- Pause count, total pause duration, current state
- Per-monitor states (CPU, memory, custom monitors)
- Killed task history for inspection

**No hidden magic** - Throttle is a transparent, configurable executor that adapts to your needs.

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.sdeonvacation</groupId>
    <artifactId>throttle</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Basic Usage

```java
import io.github.throttle.service.api.*;
import io.github.throttle.service.base.*;
import io.github.throttle.service.factory.*;
import java.time.Duration;
import java.util.concurrent.Executors;

// Create a throttle
ThrottleService executor = ThrottleServiceFactory.builder()
    .workerExecutorService(Executors.newFixedThreadPool(5))
    .queueCapacity(100)
    .cpuMonitor(75, 50)          // hot=75%, cold=50%
    .memoryMonitor(70, 50)
    .hysteresis(Duration.ofSeconds(10))
    .coldMonitoringInterval(Duration.ofSeconds(5))
    .hotMonitoringDebounceInterval(Duration.ofMillis(100))
    .maxPauseCount(5)
    .taskTerminationEnabled(true)
    .build();

// Define your task
AdaptiveTask<String> task = new AdaptiveTask<String>() {
    @Override
    public String call() {
        return processChunked(items);
    }

    @Override
    protected String processChunk(List<Item> chunk) {
        // Process a chunk of work
        return process(chunk);
    }

    @Override
    protected void onPause() {
        System.out.println("Task paused due to resource constraints");
    }

    @Override
    protected void onResume() {
        System.out.println("Task resumed");
    }
};

// Submit and get result
Future<String> future = executor.submit(task);
String result = future.get();

// Shutdown when done
executor.shutdown();
executor.awaitTermination(30, TimeUnit.SECONDS);
```

### Real-World Application Example

Here's how a typical web application would use Throttle to offload heavy background tasks:

```java
@Service
public class OrderService {
    private final ThrottleService backgroundExecutor;

    @Autowired
    public OrderService() {
        // Initialize Throttle once at application startup
        this.backgroundExecutor = ThrottleServiceFactory.builder()
            .workerExecutorService(Executors.newFixedThreadPool(3))
            .cpuMonitor(75, 50)
            .memoryMonitor(70, 50)
            .build();
    }

    // Main business logic - runs immediately
    public Order createOrder(OrderRequest request) {
        Order order = processOrderImmediately(request);

        // Submit heavy background tasks to Throttle
        // These run when resources are available, without blocking business logic
        backgroundExecutor.submit(
            new EmailNotificationTask(order, Priority.HIGH, 50)
        );
        backgroundExecutor.submit(
            new InventoryUpdateTask(order.getItems(), Priority.MEDIUM, 100)
        );
        backgroundExecutor.submit(
            new AnalyticsReportTask(order, Priority.LOW, 200)
        );

        return order;  // Return immediately, background tasks handled by Throttle
    }

    @PreDestroy
    public void cleanup() {
        backgroundExecutor.shutdown();
        backgroundExecutor.awaitTermination(30, TimeUnit.SECONDS);
    }
}
```

**Benefits of this approach**:
- ✅ Business logic (order creation) completes immediately
- ✅ Background tasks (emails, analytics) don't consume resources needed for critical operations
- ✅ System remains responsive even under high load
- ✅ Throttle automatically pauses background tasks if CPU/memory spike
- ✅ Low-priority analytics don't interfere with high-priority notifications

## Architecture

### Core Components

- **ThrottleService**: Main entry point, manages task submission and execution lifecycle
- **MonitoringCoordinator**: Monitors system resources (CPU/memory) and determines pause/resume state
- **ExecutionCoordinator**: Coordinates task execution, pause/resume, and termination
- **TaskExecutor**: Executes individual tasks with pause/resume support
- **AdaptiveTask**: Base class for tasks that support chunked execution with pause points

### Monitoring Strategy

The executor uses a checkpoint-driven monitoring approach to minimize CPU overhead:

1. **Checkpoint-Driven Monitoring**: Monitors are sampled only when tasks reach chunk boundaries (checkpoints), not continuously
2. **Debounced Sampling**: Multiple workers hitting checkpoints simultaneously trigger only one monitor sample (100ms debounce window)
3. **COLD Mode Resume Detection**: While paused, a dedicated control plane thread polls monitors every 5 seconds to detect when resources cool down

This approach ensures near-zero monitoring overhead - monitors are only checked at natural pause points in task execution.

### Task Lifecycle

```
SUBMITTED → QUEUED → RUNNING → [PAUSED] → RUNNING → COMPLETED/FAILED/KILLED
```

- Tasks can be paused multiple times during execution
- Tasks exceeding `maxPauseCount` are terminated with `TaskTerminatedException`
- Failed tasks trigger `onError()` callback

## Configuration Options

**All parameters are client-controlled** - customize every aspect to fit your application's needs:

| Option | Description | Default | Your Control |
|--------|-------------|---------|--------------|
| `workerExecutorService` | Thread pool for task execution | Fixed pool of 2 threads | **Provide your own or use default** |
| `controlPlaneExecutorService` | Thread pool for monitoring/coordination | Fixed pool of 2 threads | **Provide your own or use default** |
| `queueCapacity` | Maximum tasks in queue | 100 | **Set based on your workload** |
| `cpuMonitor(hot, cold)` | CPU thresholds (%) | 75, 50 | **Tune for your hardware** |
| `memoryMonitor(hot, cold)` | Memory thresholds (%) | 70, 50 | **Tune for your heap size** |
| `hysteresis` | Minimum time in state before transition | 10 seconds | **Adjust stability vs responsiveness** |
| `coldMonitoringInterval` | Resume detection polling (while paused) | 5 seconds | **Balance between latency & overhead** |
| `hotMonitoringDebounceInterval` | Min time between checkpoint samples | 100ms | **Prevent redundant sampling** |
| `maxPauseCount` | Max pauses before task termination | 5 | **Control task killing behavior** |
| `taskTerminationEnabled` | Enable automatic task termination | true | **Enable/disable as needed** |
| `starvationThreshold` | Time before priority boost | 2 hours | **Prevent task starvation** |
| `overflowPolicy` | Queue overflow behavior | REJECT | **REJECT, DISCARD_OLDEST, or BLOCK** |

### Thread Pool Examples

You have **complete control** over the thread pools:

```java
// Example 1: Large worker pool for high throughput
ThrottleService executor = ThrottleServiceFactory.builder()
    .workerExecutorService(Executors.newFixedThreadPool(20))
    .controlPlaneExecutorService(Executors.newFixedThreadPool(2))
    .build();

// Example 2: Cached thread pool for dynamic workloads
ThrottleService executor = ThrottleServiceFactory.builder()
    .workerExecutorService(Executors.newCachedThreadPool())
    .build();

// Example 3: Custom thread factory with naming
ThreadFactory factory = new ThreadFactoryBuilder()
    .setNameFormat("my-app-worker-%d")
    .build();
ThrottleService executor = ThrottleServiceFactory.builder()
    .workerExecutorService(Executors.newFixedThreadPool(10, factory))
    .build();

// Example 4: Use defaults (2 threads each)
ThrottleService executor = ThrottleServiceFactory.builder()
    .cpuMonitor(80, 55)  // Just configure thresholds
    .build();
```

## Simulator

The project includes a comprehensive simulator for testing and demonstration. See [simulator/docs/README.md](simulator/docs/README.md) for details.

The simulator provides:
- Real-time dashboard with WebSocket updates
- 12 test scenarios (7 positive, 5 edge cases)
- Independent CPU and memory load generators
- Live metrics and monitoring

To run the simulator:

```bash
cd simulator
mvn spring-boot:run
```

Then open http://localhost:8080/api/simulator/dashboard

## Requirements

- Java 17+
- Maven 3.6+

## Building

```bash
mvn clean install
```

## Testing

```bash
mvn test
```

## License

[Apache License 2.0](LICENSE)

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Support

For issues, questions, or contributions, please visit the [GitHub repository](https://github.com/sdeonvacation/throttle).
