# A Background Job Was Crashing Our JVM Every Week — Until We Taught It to Stop

*Some Java services don’t fail because of traffic. They fail because background jobs don’t know when to stop.*

> A background job should never be able to take down your production system.

Ours did. Every week.

At SAP, we ran a multi-tenant service processing thousands of tenants. Every morning, a scheduled job kicked off — telemetry, cleanup, routine work. Nothing unusual.

Until it overlapped with real traffic.

Then, within minutes:

- Heap jumped from **60% → 94%**
- GC went into panic mode
- Latency spiked **5×**
- **OOMKilled**

No memory leak. No bad deploy. Just a background job that didn’t know when to stop.

## If This Feels Familiar, You’re Already at Risk

If you’ve ever:

- Used `@Scheduled` and assumed it’s safe
- Configured a thread pool and felt “this should handle it”
- Relied on auto-scaling to absorb spikes

You’re sitting on the same failure mode we were.

The JVM didn’t fail. Our scheduling model did.

## The Problem Nobody Talks About

We had everything “right”:

- Thread pools
- Bounded queues
- Scheduled jobs
- Horizontal scaling

And yet, the system still collapsed.

Because none of these answer one critical question:

> **Should this job continue right now?**

They control how much work runs.

They don’t react to when the system is under pressure.

## Why the Usual Fixes Don’t Work

Let’s cut through the usual advice:

| Tool | What You Think It Does | What It Actually Misses |
|------|-------------------------|--------------------------|
| Rate limiting | Controls load | Ignores CPU/memory pressure |
| Bulkheads | Limits concurrency | Still burns resources under stress |
| Thread pools | Caps parallelism | Even 1 thread can OOM you |
| Auto-scaling | Adds capacity | Too slow, too expensive |
| Spring Batch | Manages jobs | No runtime awareness |

All of these control throughput.

None of them understand pressure.

Background jobs don’t fail loudly. They fail by slowly starving everything else.

## The Shift That Changed Everything

We stopped asking:

> **How do we run this more efficiently?**

And started asking:

> **Does this job even have permission to run right now?**

That one shift changed everything.

## The Fix (In One Sentence)

Pause background work when CPU or memory crosses a threshold. Resume when the system recovers.

Not slow down.

Not retry.

Pause. Completely.

Zero CPU usage. Zero contention.

## The Pattern: Checkpoints That Can Say “Stop”

Instead of running a massive job in one go:

1. Break it into chunks
2. After each chunk, check system health
3. If stressed, pause execution
4. Resume later from the same point

You don’t control execution.

You control permission to continue.

Why this works:

- Java can’t pause a thread mid-execution
- So you create safe pause points
- Like checkpoints in a game: not mid-jump, only at safe boundaries

![Checkpoint-driven backpressure diagram](./diagrams/Tenant%20Resource%20Check-2026-04-23-094631.png)

*Workers process one chunk, check pressure, pause if hot, and resume when healthy.*

## What This Looked Like in Production

![Before and after resource behavior](./diagrams/before-after-system-2026-04-23-093811.png)

*Before: background work kept pushing through heap pressure until GC thrash turned into OOM kills. After: work paused at the threshold, traffic stayed healthy, and the job finished later but safely.*

### Before

```text
09:00  Batch starts
09:12  Heap at 78%
09:15  Traffic spike
09:17  Heap at 94%
09:18  OOMKilled
```

### After

```text
09:00  Batch starts
09:15  Heap crosses threshold
09:15  PAUSE triggered
       (background work fully stops)
09:22  System recovers
09:22  RESUME triggered
09:35  Job completes (late, but safe)
```

Same system. Same workload.

**80% fewer OOM incidents.**

Auto-scaling reacts.

This prevents.

## The Trade-Off Most People Try to Avoid

This is where people hesitate.

Your code must become chunkable.

No shortcuts.

You need:

- Idempotent batches
- Clear boundaries
- Resume-safe execution

If your job is one giant function, this pattern won’t save you.

## What Most Engineers Get Wrong

They obsess over:

- Chunk size
- Overhead
- Implementation details

Wrong focus.

The real question is:

> **Can my background work behave like a good citizen?**

If not, your system will fail under pressure.

## Where This Actually Works

Use this for:

- ETL pipelines
- Batch APIs
- Cleanup jobs
- Migrations
- Scheduled processing

Avoid it for:

- Ultra-low latency paths
- Non-interruptible logic
- Fire-and-forget tasks

## The Bigger Insight

The JVM gives you `ExecutorService`.

That abstraction assumes:

- Dedicated machines
- Predictable load
- No contention

That world doesn’t exist anymore.

Today you have:

- Containers
- Shared CPU
- Hard memory limits

“Just run it” is no longer a valid strategy.

## The Hard Truth

Your system didn’t crash because Java failed.

It crashed because your background jobs were selfish.

They consumed resources blindly.

They never asked:

> **Is now a good time?**

## Final Thought

Auto-scaling throws hardware at the problem.

This approach does something better:

It teaches your system restraint.

And in distributed systems, restraint is survival.

## Want to Try It?

We built this into an open-source library:

👉 https://github.com/sdeonvacation/throttle

Start with the simulator.

Watch it pause and resume.

Then apply it to one job — not everything.

## One Last Thing

If your background jobs run in the same JVM as your APIs, you don’t have a performance problem.

You have a priority problem.
