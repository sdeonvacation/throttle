# Throttle

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.0.0--SNAPSHOT-brightgreen.svg)](https://search.maven.org/artifact/io.github.throttle/throttle)

A Java library for adaptive task execution with resource-aware monitoring and automatic pause/resume capabilities.

## Overview

Throttle is a sophisticated task execution framework that automatically adapts to system resource availability. It monitors CPU and memory usage and intelligently pauses/resumes task execution to prevent system overload, making it ideal for resource-intensive batch processing applications.

## Key Features

- **Automatic Resource Monitoring**: Continuously monitors CPU and memory usage
- **Adaptive Pause/Resume**: Automatically pauses task execution when resources are constrained and resumes when resources become available
- **Priority-Based Scheduling**: Supports HIGH, MEDIUM, and LOW priority tasks
- **Task Termination**: Configurable task killing for tasks that pause too frequently
- **Configurable Thresholds**: Customizable hot/cold thresholds for CPU and memory
- **Hysteresis Support**: Prevents rapid oscillation between pause/resume states
- **Queue Management**: Bounded queues with configurable overflow policies
- **Comprehensive Monitoring**: Built-in metrics for task completion, failures, and resource usage

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.sdeonvacation</groupId>
    <artifactId>throttle</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Basic Usage

```java
import io.github.throttle.service.api.*;
import io.github.throttle.service.base.*;
import io.github.throttle.service.factory.*;
import java.time.Duration;
import java.util.concurrent.Executors;

// Create an throttle
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

| Option | Description | Default |
|--------|-------------|---------|
| `workerExecutorService` | Underlying thread pool for task execution | Fixed pool of 2 threads |
| `controlPlaneExecutorService` | Thread pool for pause/resume coordination and monitoring | Fixed pool of 2 threads |
| `queueCapacity` | Maximum tasks in queue | 100 |
| `cpuMonitor(hot, cold)` | CPU thresholds (%) | 75, 50 |
| `memoryMonitor(hot, cold)` | Memory thresholds (%) | 70, 50 |
| `hysteresis` | Minimum time in state before transition | 10 seconds |
| `coldMonitoringInterval` | Resume detection polling interval (while paused) | 5 seconds |
| `hotMonitoringDebounceInterval` | Min time between checkpoint samples (prevents redundant sampling) | 100ms |
| `maxPauseCount` | Max pauses before task termination | 5 |
| `taskTerminationEnabled` | Enable automatic task termination | true |

**Note**: Both executor services can be customized using `Executors.newFixedThreadPool(n)` or other `ExecutorService` implementations. For example:
- `.workerExecutorService(Executors.newFixedThreadPool(5))` for 5 worker threads
- `.controlPlaneExecutorService(Executors.newFixedThreadPool(1))` for single-threaded control plane

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
