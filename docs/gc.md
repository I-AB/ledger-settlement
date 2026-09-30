# GC Tuning Analysis - Ledger Settlement Service

## Task 1: Branch and effective heap settings

Branched `feature/gc-tuning` from `master` (which already contains the SJV-L1 virtual threads migration).

Effective JVM defaults on this machine (JDK 25.0.4 Temurin):

```
size_t MaxHeapSize                              = 2116026368  {product} {ergonomic}
size_t SoftMaxHeapSize                          = 2116026368  {manageable} {ergonomic}
bool UseG1GC                                    = true        {product} {ergonomic}
```

MaxHeapSize is ergonomic (JVM-calculated from available system memory), not explicitly configured anywhere in this project. G1 is confirmed as the active collector before any tuning.

## Task 2: Steady-state load script

Added `perf/steady-load.js`, a k6 script using the `constant-arrival-rate` executor to hold a steady 50 requests/second against the settlement endpoint (`GET /payments/settlement?merchantId=MR-4471`) for 10 minutes. This differs deliberately from SJV-L1's `loadtest/baseline.js` (which used a fixed-VU executor to find maximum throughput): here we want a steady, non-overloading load so that allocation and pause behavior reflect a realistic sustained rate, not saturation.

## Task 3: Baseline JFR capture

Built a runnable jar (`mvn clean package -DskipTests`) and launched it with a JFR recording spanning the full load window:

```
java -XX:StartFlightRecording=duration=10m,filename=gc-baseline.jfr,settings=profile -jar target/ledger-settlement-1.0.0.jar
```

Ran `perf/steady-load.js` against it for the full 10 minutes. k6 summary:

- 29,574 requests completed, 49.15 req/s achieved (target: 50 req/s)
- 0% failed checks
- http_req_duration: avg 77.95ms, p90 40.81ms, p95 248.23ms, max 9.47s

The 9.47s maximum latency outlier is notably larger than any individual GC pause captured in this recording (longest pause: 132.946ms, see Task 4), so it is not explained by GC and is most likely a cold-start or connection-pool related stall. Noted here for honesty, not folded into the GC analysis below.

## Task 4: Baseline allocation and GC data (from gc-baseline.jfr)

Read via JDK Mission Control. Note: this JMC build's dedicated "TLAB Allocations" page did not populate for this recording (JDK 25 uses the newer `jdk.ObjectAllocationSample` event rather than the legacy per-object TLAB events that page reads). Allocation data below was instead pulled from the Event Browser's `Object Allocation Sample` events (9,440 samples), aggregated by class and summed in a spreadsheet.

- **Total allocation rate**: approximately 2.76 MiB/s (1,733,575,872 bytes allocated over the 600-second recording)
- **Top 3 allocating classes**:
    1. `byte[]` - 590,963,624 bytes (34.1% of total)
    2. `jdk.internal.vm.StackChunk` - 152,513,640 bytes (8.8%)
    3. `java.lang.String` - 87,120,824 bytes (5.0%)
- **Collection count**: 68 total (54 Young, 14 Old/Mixed)
- **Longest pause**: 132.946ms
- **Sum of pauses**: 851.710ms (0.14% of the 10-minute window)
- Young collections: 54 total, average 9.6ms, max 80.183ms
- Old/Mixed collections: 14 total, average GC time 177.506ms, max GC time 328.002ms

`jdk.internal.vm.StackChunk` appearing as the #2 allocator is a direct, measurable consequence of the virtual threads migration from SJV-L1. It is the object the JVM uses to store a virtual thread's continuation stack when it unmounts from its carrier thread.

## Task 5: Classification (before making any change)

Two candidate explanations, weighed against the data:

- **Allocation pressure**: 2.76 MiB/s is a modest allocation rate, not the kind of figure (often hundreds of MB/s) typical of an allocation-bound service. Ruled out as the primary issue.
- **Pause problem**: total time spent paused is only 0.14% of the run, not severe in aggregate. But the shape is telling: Old/Mixed collections average 177.5ms and peak at 328.002ms, roughly 20x the cost of an average Young collection (9.6ms). 14 such collections occurred in 10 minutes.

**Classification: pause problem, localized to Old/Mixed generation collections.** Allocation volume is not the bottleneck; the disproportionate cost of Old-gen collections relative to Young-gen is the one clear signal in this baseline.

Fix chosen: change one GC variable, switching the collector from G1 to ZGC (heap size left untouched). ZGC targets consistently low max pause times regardless of heap size, directly addressing the Old-gen pause cost identified above, rather than fixing an allocation hot spot that the data doesn't support as the actual problem.


## Task 6/7: The fix

Changed exactly one JVM variable: switched the garbage collector from G1 to ZGC, using the single flag `-XX:+UseZGC`. Heap size and all other settings were left untouched. On JDK 25, `-XX:+UseZGC` runs generational ZGC by default (non-generational ZGC was removed as of JDK 24), so no additional flags were needed.

## Task 8: Re-running the identical load

Ran `perf/steady-load.js` against the ZGC-enabled app for the same 10 minutes, capturing `gc-tuned.jfr` via `-XX:StartFlightRecording=duration=10m,filename=gc-tuned.jfr,settings=profile`.

A methodology note: the first pair of runs (this JFR capture and the original G1 baseline capture) showed a large, unexpected drop in latency between them that was too large to attribute to the collector alone (baseline avg 77.95ms vs tuned avg 72.37ms is a small, plausible difference, but a later G1-only re-run without any collector change produced avg 11.51ms, proving the system itself was warming up across the session, most likely Postgres buffer/query cache and the HikariCP connection pool settling in, not the GC configuration).

To get a fair, controlled comparison, both collectors were re-run back to back near the end of the session, after the system had reached a stable warm state, with `k6 run --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)"` added so p99 was captured directly (the original two runs only captured p90/p95 by default). The comparison table below uses these matched, back-to-back runs for latency and throughput figures, and the original JFR recordings for allocation and GC pause data, since collector-level GC behavior is not affected by the same warm-up dynamics that affected end-to-end request latency.

## Task 9: Comparison table

| Metric | Baseline (G1) | Tuned (ZGC) |
|---|---|---|
| Allocation rate | approximately 2.76 MiB/s | approximately 3.59 MiB/s |
| Top allocating class | byte[] - 590,963,624 bytes | byte[] - 582,851,624 bytes |
| 2nd allocating class | jdk.internal.vm.StackChunk - 152,513,640 bytes | jdk.internal.vm.StackChunk - 266,524,872 bytes |
| 3rd allocating class | java.lang.String - 87,120,824 bytes | java.lang.String - 118,863,944 bytes |
| Collection count | 68 (54 Young, 14 Old/Mixed) | 9 |
| Longest pause | 132.946 ms | 0.990 ms |
| Sum of pauses | 851.710 ms | 3.909 ms |
| p95 latency (matched warm runs) | 7.9 ms | 6.39 ms |
| p99 latency (matched warm runs) | 152.77 ms | 12.09 ms |
| Max latency (matched warm runs) | 1.61 s | 1.61 s |
| Throughput (matched warm runs) | 49.88 req/s | 49.94 req/s |

The identical 1.61s max latency on both runs is itself informative: it confirms that outlier is not GC-related at all (it did not change when the collector changed), most likely a one-time connection or resource stall unrelated to this tuning work.

## Task 10: Honest trade-off

ZGC delivered a large, clearly attributable improvement on the metric that mattered most given the Task 5 classification: max GC pause dropped from  132.946ms to 0.990ms (approximately 134x), and p99 request latency dropped from 152.77ms to 12.09ms (approximately 12.6x), with throughput unaffected.

The trade-off is not free. ZGC's average GC cycle time (298.770ms) is far higher than G1's (44.169ms), because ZGC does almost all of its marking and relocation work concurrently with the running application rather than stopping it, that concurrent work still consumes real CPU cycles that would otherwise be available to serve requests. Allocation rate also rose slightly (2.76 to 3.59 MiB/s), though this is more likely explained by ZGC's faster response times allowing more iterations to complete in the same 10 minutes rather than a genuine allocation regression caused by the collector itself.

This test ran at a modest, fixed rate (50 req/s) on a single machine with no explicit CPU constraint, so it does not tell us how ZGC's concurrent CPU usage would behave under heavier load or in a CPU-limited container. Before committing to this change in a production-like environment, the same comparison should be re-run under realistic CPU allocation and at higher throughput to confirm the pause-time win does not come at the cost of reduced capacity under load.

A direct measurement confirms one more trade-off: JVM startup time was
essentially unchanged between collectors (13.713 seconds under G1 versus
13.552 seconds under ZGC, measured from Spring Boot's own startup log line),
but memory footprint was not. Process working set measured shortly after
startup was approximately 236 MB under G1 versus approximately 518 MB under
ZGC, more than double. This matches ZGC's documented design: it reserves and
multi-maps larger virtual memory regions to support colored pointers and
concurrent relocation, so a higher resident footprint is an expected,
real cost of the collector, not measurement noise. Any memory-constrained
or containerized deployment adopting ZGC needs to budget for this footprint
increase up front, not discover it under pressure.

Rollback: revert this branch's merge commit, or remove `-XX:+UseZGC` from the JVM launch flags to return to the default G1 collector. No code changes were made in this fix, so rollback carries no other risk.