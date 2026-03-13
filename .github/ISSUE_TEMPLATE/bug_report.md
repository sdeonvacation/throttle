---
name: Bug Report
about: Create a report to help us improve
title: '[BUG] '
labels: bug
assignees: ''
---

## Bug Description
A clear and concise description of what the bug is.

## To Reproduce
Steps to reproduce the behavior:
1. Create executor with configuration '...'
2. Submit task '...'
3. Observe behavior '...'
4. See error

## Expected Behavior
A clear and concise description of what you expected to happen.

## Actual Behavior
What actually happened instead.

## Code Sample
```java
// Minimal code sample that reproduces the issue
ThrottleService executor = ThrottleServiceFactory.builder()
    .cpuMonitor(75, 50)
    .build();

// Your task implementation
```

## Environment
- **Throttle Version**: [e.g., 1.0.0]
- **Java Version**: [e.g., 17.0.5]
- **OS**: [e.g., Ubuntu 22.04, macOS 13.0, Windows 11]
- **CPU**: [e.g., Intel i7, Apple M1]
- **Memory**: [e.g., 16GB]

## Configuration
```java
// Your executor configuration
ThrottleServiceFactory.builder()
    .workerExecutorService(...)
    .queueCapacity(...)
    .cpuMonitor(...)
    .memoryMonitor(...)
    .build();
```

## Logs
```
Paste relevant logs here
```

## Metrics
```
Paste executor metrics if available:
- tasksCompleted:
- tasksFailed:
- tasksKilled:
- pauseCount:
- queueSize:
```

## Additional Context
Add any other context about the problem here, such as:
- Does this happen consistently or intermittently?
- Have you tried different configurations?
- Are there any workarounds?

## Possible Solution
If you have ideas on how to fix the issue, please share them here.
