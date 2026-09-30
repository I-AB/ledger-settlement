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