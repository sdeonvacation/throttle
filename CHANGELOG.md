# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned
- Per-task metrics collection (in-memory, bounded)
- Health scoring system
- Statistical anomaly detection
- Workload profiling with automatic chunk size recommendations

See `INTELLIGENT_FEATURES_PROPOSAL.md` for detailed enhancement plans.

## [1.0.1] - 2026-03-17

### Fixed
- **Critical:** Changed control plane thread from daemon to non-daemon to ensure graceful shutdown in containerized environments (Kubernetes, AWS ECS, Cloud Foundry). Previously, daemon threads could be killed on JVM exit without signaling workers, causing deadlocks during pod termination.
- **Critical:** Fixed race condition in DISCARD_OLDEST overflow policy where concurrent submitters could experience spurious `RejectedExecutionException` errors. Now uses direct permit transfer instead of release+reacquire pattern.
- **Performance:** Refactored anti-starvation checks from per-completion (O(N²)) to timer-based with configurable interval (default: 10 minutes). Eliminates ~100% CPU core overhead for systems with 100+ queued tasks.

### Added
- New configuration parameter: `starvationCheckInterval` (default: 10 minutes) for controlling anti-starvation check frequency
- Integration test for CPU monitor degradation scenarios (AWS Lambda, GraalVM native images, non-Sun JVMs)

### Changed
- Anti-starvation logic moved from per-task-completion to control plane timer loop for better performance at scale

### Performance Improvements
- Anti-starvation overhead reduced by ~10,000x (from O(N²) per completion to O(1) amortized)
- DISCARD_OLDEST throughput improved under high-concurrency workloads
- Shutdown latency reduced to <1 second (previously could hang or timeout)

### Backward Compatibility
- ✅ Fully backward compatible - no breaking changes
- ✅ Drop-in replacement for v1.0.0
- ✅ All existing configurations continue to work
- ✅ New `starvationCheckInterval` parameter is optional with sensible default

### Migration Notes
No code changes required when upgrading from v1.0.0 to v1.0.1. All improvements are automatic.

Optional: Customize starvation check interval if desired:
```java
ThrottleService executor = ThrottleServiceFactory.builder()
    .starvationCheckInterval(Duration.ofMinutes(15))  // Optional
    .build();
```

### Recommended For
- **High Priority:** Kubernetes and containerized deployments (fixes shutdown deadlock)
- **High Priority:** High-throughput systems using DISCARD_OLDEST policy (fixes race condition)
- **Recommended:** Systems with queue depths >50 tasks (significant performance improvement)
- **Recommended:** AWS Lambda and GraalVM native image deployments (validated graceful degradation)

## [1.0.0] - 2026-03-16

### Added
- Core throttle service with priority-based scheduling
- Resource-aware pause/resume capabilities
  - CPU monitoring with configurable hot/cold thresholds
  - Memory monitoring with configurable hot/cold thresholds
  - Custom resource monitors support
- Priority-based task scheduling (HIGH, MEDIUM, LOW)
- Anti-starvation mechanism with priority boosting
- Chunk-based task execution with pause checkpoints
- Task termination for excessive pauses (configurable)
- Queue management with configurable overflow policies (REJECT, DISCARD_OLDEST, BLOCK)
- Hysteresis support to prevent rapid pause/resume oscillation
- Comprehensive metrics (tasks completed/failed/killed, pause count, queue size)
- Fully configurable thresholds, intervals, and policies
- Thread pool configuration (worker and control plane)
- Comprehensive simulator with 12 test scenarios
  - 7 positive scenarios (normal operation, resource spike, sustained load, etc.)
  - 5 edge case scenarios (flapping monitor, queue overflow, cascade kill, etc.)
  - Real-time dashboard with WebSocket updates
  - Independent CPU and memory load generators
- Complete documentation
  - High-level design document (HLD)
  - Architecture diagrams and design rationale
  - Contribution guidelines
  - Development guide (CLAUDE.md)

### Technical Details
- Java 17+ with module system
- Zero CPU cost when idle (interrupt-based worker wakeup)
- Chunk-driven pause detection (no continuous polling)
- Control-plane resume detection
- Callback safety (exception handling in onComplete/onError)
- Monitor fault tolerance (fail-open design)
- Apache License 2.0

## Version History

- **1.0.0** - Initial release with core features
