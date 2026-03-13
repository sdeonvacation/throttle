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

See `INTELLIGENT_FEATURES_PROPOSAL_V2.md` for detailed enhancement plans.

## [1.0.0] - TBD

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
