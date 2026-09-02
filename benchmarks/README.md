# Benchmarks

**Status: harness only. No published numbers.**

There are no benchmark figures in this repository, on the website, or in the README, because
none have been taken under conditions worth publishing. A number without its methodology is
marketing, and Kryon does not have earned performance claims to make yet.

## What will be measured

| Dimension | Why it matters |
|---|---|
| Process startup overhead | The floor on any `execute` call. Kryon's share of it should be small next to `fork`/`exec`. |
| `execute` round trip | The common case: start, capture, reap. |
| Streaming throughput | Bytes per second through the bounded queue, and the cost of the backpressure. |
| Memory under load | Peak RSS with a capped output flood. The cap is supposed to make this flat. |
| Concurrent sessions | How many live `Process` objects a runtime holds before it degrades. |
| Cancellation latency | Time from `cancel()` to the child actually being gone. |
| Terminal parsing | Bytes per second through the emulator, once it exists. |

## Methodology, when numbers are published

Every figure will carry:

- the machine: CPU, cores, RAM, operating system and kernel version;
- the language runtime version;
- the exact benchmark source, in this directory;
- the number of runs and which statistic is reported (median, not mean — process startup has
  a long tail that a mean hides);
- what it was compared against, at which version;
- whether the cache was warm, and whether the machine was otherwise idle.

Comparisons will be against the honest baseline — the language's own standard library — and
will say plainly where Kryon is slower. It will be, in places: a bounded queue and a
termination guarantee are not free, and a comparison that hides that is not a comparison.

## Running them

Nothing here yet. When there is, it will be:

```bash
python benchmarks/python/run.py
```

with no dependency beyond the standard library, so that the benchmark harness does not itself
become a thing to maintain.

## Position on optimisation

Measure first. Kryon has not been profiled, and the correct response to a suspected
performance problem is a measurement, not a patch. If you have one, the
[performance issue template](../.github/ISSUE_TEMPLATE/performance.yml) asks for exactly what
makes it actionable.

Where a deliberate trade-off exists, it is documented at the point it is made rather than
here: the bounded queue caps memory at the cost of throughput when a consumer is slow, and
the termination sequence costs a round trip on every timeout. Both are the right trade, and
both are visible in the numbers when they arrive.
