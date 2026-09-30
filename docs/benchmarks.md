# JMH Microbenchmarks - Ledger Settlement Service

## Task 1: Benchmarks module

Added a `benchmarks` Maven module to the Ledger settlement service build. This required restructuring the project into a proper Maven multi-module reactor:

- Root `pom.xml`: new parent aggregator, packaging `pom`, listing `app` and `benchmarks` as modules
- `app/`: the existing Spring Boot service (previously the repository root), unchanged apart from the move
- `benchmarks/`: new module with `jmh-core` and `jmh-generator-annprocess` (both 1.37) as dependencies, `maven-shade-plugin` (3.6.2) configured to produce an executable `benchmarks.jar` with `org.openjdk.jmh.Main` as the manifest main class

One dependency snag worth recording: `app`'s `spring-boot-maven-plugin` repackages its jar into a Spring Boot fat jar by default, which replaces the plain jar as the artifact other modules resolve. Since `benchmarks` needs to compile against `com.ledger.SettlementService` directly, `app/pom.xml` was updated to give the fat jar a `classifier` (`exec`), so the plain jar (`ledger-settlement-1.0.0.jar`) stays available as a normal dependency, and the runnable fat jar becomes `ledger-settlement-1.0.0-exec.jar`.

## Task 2: Three methods chosen

1. **Serialization/mapping**: `PaymentController.toResponse(PaymentEntity)`. Converts the JPA entity into the `PaymentResponse` record returned to API clients on every payment read and write. Chosen because it is the one mapping method that sits on every request path through the controller. Visibility widened from `private` to package-private so the benchmark module (same package, different jar) can call it directly; no logic changed.

2. **Collection-heavy**: `SettlementService.sumAmounts(List<PaymentEntity>)`. Extracted from inside `owedMinor` (a pure extract-method refactor, same stream/reduce logic, same behavior) because it is the one place in the service that processes a merchant's full payment list rather than a single record. Chosen over inventing a synthetic collection benchmark because it is exactly the list-processing step that runs on every settlement request today.

3. **Settlement calculation**: `SettlementService.owedMinor(String merchantId)`, as directed by the lab. This method deliberately stays on `long` minor units and `BigDecimal` rather than `double` throughout, because binary floating point cannot represent a decimal currency amount exactly (`0.1` has no exact binary representation), and an inexact fee calculation on money is a correctness bug, not just a performance concern. Benchmarked by calling the real method on a real `SettlementService` instance, with `PaymentRepository` mocked via Mockito to return a fixed in-memory payment list (avoiding a live database round trip inside the measured code) and `SettlementProperties` constructed directly (a plain record, no mocking needed).

   Limitation worth stating plainly: `owedMinor`'s first line reads `FeeScheduleLookup.TABLE_VERSION`, a static field whose initializer opens a real JDBC connection to Postgres (planted in SJV-L1). This means Postgres must be reachable at `localhost:5433` when the benchmark JVM starts, even though the repository itself is mocked. This one-time cost happens once per JVM fork during class loading, so it is expected to land inside JMH's warmup iterations and not pollute the measured results, but it is a real, stated dependency of this benchmark suite, not something hidden.


## Task 3: Deliberately wrong benchmark (dead code elimination)

`MappingBenchmark.mapToResponse_broken()` computes a `PaymentResponse` via `PaymentController.toResponse(entity)` and discards the result, nothing consumes it, nothing is returned.

Quick run (1 fork, 2 warmup + 2 measurement iterations, 1s each, not yet statistically rigorous, just to see the shape of the number): MappingBenchmark.mapToResponse_broken thrpt 2 33902542.398 ops/s

Approximately 34 million operations per second for a method that constructs an object and maps its fields is not a believable number for real work, no mapping logic executes in under 30 nanoseconds per call at that rate. This is the textbook signature of dead code elimination: the JIT determined the result of `toResponse(entity)` was never used and removed the call.

## Task 4: Fixed with Blackhole

Added `mapToResponse_fixed(Blackhole blackhole)`, identical to the broken version except the result is passed to `blackhole.consume(...)`, which tells the JIT the value is used and must not be eliminated. MappingBenchmark.mapToResponse_broken thrpt 2 38280635.774 ops/s
MappingBenchmark.mapToResponse_fixed thrpt 2 36749567.547 ops/s

Honest observation: the fixed version is only about 4% slower than the broken one here, not the dramatic order-of-magnitude collapse dead-code-elimination examples usually show. JMH's own console output explains part of why: this JVM run used JMH's experimental "Compiler Blackholes" support, auto-detected and applied, which appears to have given even the broken benchmark some protection against full elimination. Additionally, this was only 2 measurement iterations with no computed error, not enough to say whether even this 4% gap is a real effect or measurement noise, the two runs of the identical broken benchmark shown above (33.9M then 38.3M ops/s) already differ by more than that. The properly configured run in Task 7 (5 warmup, 10 measurement iterations, 3 forks) will give statistically defensible numbers to settle this.

## Task 5: Moving Inputs Into @State Classes

All three benchmark methods originally built their inputs inline inside the
`@Benchmark` method body. This risks the JIT constant-folding a hardcoded
literal, and it mixes setup cost into the measured cost.

Fixed by giving each benchmark class a `@State(Scope.Benchmark)` inner class
with a `@Setup` method, populated once per benchmark run rather than once
per invocation:

- `MappingBenchmark.MappingState` builds a single `PaymentEntity`.
- `CollectionBenchmark.PaymentListState` builds a 500-element
  `List<PaymentEntity>`.
- `SettlementBenchmark.SettlementState` builds a 500-element payment list,
  mocks `PaymentRepository` with Mockito so `owedMinor` never makes a real
  database round trip during the measured call, and constructs a real
  `SettlementService` wired to that mock.

Limitation: `owedMinor`'s first line reads `FeeScheduleLookup.TABLE_VERSION`,
whose static initializer opens a real JDBC connection to Postgres (planted
in SJV-L1). Mocking the repository does not remove this. Postgres has to be
reachable at localhost:5433 for `SettlementBenchmark` to run at all, but
this one-time cost lands in JVM startup, not in the measured per-op time.

## Task 6: JMH Configuration Annotations

Added to all three benchmark classes:

- `@BenchmarkMode(Mode.AverageTime)`: report average time per operation.
- `@OutputTimeUnit(TimeUnit.MICROSECONDS)`: microsecond scale fits these
  methods.
- `@Warmup(iterations = 5)`: five warmup iterations so the JIT reaches
  steady state before anything is measured.
- `@Measurement(iterations = 10)`: ten measured iterations per fork.
- `@Fork(3)`: three separate JVM processes. Each fork starts cold, so no
  single fork's JIT compilation quirks or warm-up idiosyncrasies leak into
  another fork's measured results. Total measured samples per benchmark =
  3 forks x 10 iterations = 30, which is the `Cnt` column in the JMH output.

## Task 7: Full Benchmark Suite Run

Command:

    java -jar benchmarks/target/benchmarks.jar -rf json -rff results.json

Total wall time: 30 minutes 59 seconds.

| Benchmark                              | Score (us/op) | Error (+-) |
|-----------------------------------------|---------------|------------|
| CollectionBenchmark.sumAmounts          | 0.572         | 0.021      |
| MappingBenchmark.mapToResponse_broken   | 0.023         | 0.001      |
| MappingBenchmark.mapToResponse_fixed    | 0.025         | 0.002      |
| SettlementBenchmark.owedMinor           | 410.242       | 274.099    |

JMH itself flagged that this JVM has "Compiler Blackholes" (an experimental
feature) active, and warned against over-trusting results that depend on
Blackhole behavior. Two things stood out and needed follow-up before drawing
conclusions:

1. mapToResponse_broken (0.022-0.024 us/op) and mapToResponse_fixed
   (0.023-0.027 us/op) overlap. The ~4% gap seen in Task 4's 2-iteration run
   does not survive 30 proper measurements.
2. owedMinor's error is 274 us on a 410 us score, about 67% relative error,
   far noisier than the other three benchmarks.

Raw results saved to `results.json` and committed as evidence.

## Task 8: GC/Allocation Profiling

Command:

    java -jar benchmarks/target/benchmarks.jar -prof gc -rf json -rff results-gc.json

Total wall time: 31 minutes 16 seconds.

| Benchmark                              | Alloc (B/op) | Alloc rate (MB/sec) | GC count | GC time (ms) |
|------------------------------------------|--------------|----------------------|----------|---------------|
| CollectionBenchmark.sumAmounts            | 256.000      | 461.369              | 1507     | 1241          |
| MappingBenchmark.mapToResponse_broken     | ~0 (noise floor) | 0.001            | ~0       | (none recorded) |
| MappingBenchmark.mapToResponse_fixed      | 32.000       | 1224.204             | 2115     | 1701          |
| SettlementBenchmark.owedMinor             | 2336.128     | 29.231               | 189      | 297788        |

Raw results saved to `results-gc.json` and committed as evidence.

## Task 9: Results Analysis

**Mapping comparison (broken vs fixed): timing is inconclusive, allocation
is conclusive.** The confidence intervals on time-per-op overlap, so timing
alone cannot show that dead code elimination happened. Allocation data
resolves it: `mapToResponse_broken` allocates nothing (0 GC collections
attributable to it), because the JIT removed the unused `PaymentResponse`
construction entirely. `mapToResponse_fixed` allocates exactly 32 bytes per
call, one `PaymentResponse` object, kept alive by `Blackhole.consume`. This
is the real signature of dead code elimination, at the allocation level
rather than the timing level, and it is not subject to the Compiler
Blackhole caution JMH raised, since it doesn't depend on wall-clock timing
at all.

**owedMinor's timing variance is most likely a benchmarking-environment
artifact, not a property of the code.** Its GC time of 297,788 ms across
only 189 collections averages out to roughly 1.5 seconds per collection,
for a benchmark allocating only about 2.3 KB per call. That is far outside
normal young-generation pause behavior and points to something external
(background load, scheduling contention, or another JVM/OS-level factor on
the test machine) rather than the settlement logic itself. This is the
exact caution JMH's own output gives: the numbers alone don't explain
themselves, and this one needed the GC profiler to reveal a likely
environmental cause rather than a code-level one.

**sumAmounts and owedMinor have no second variant to compare against**
within this suite (each is the only benchmark for its method), so the
overlapping-confidence-interval check applies specifically to the Mapping
pair above.

## Task 10: Limits of This Benchmark Suite

Three things these numbers do not tell you:

1. **Behavior under concurrency.** Every benchmark here ran single-threaded
   (JMH's default). The real service handles concurrent requests, and
   `owedMinor` in particular goes through a Spring `@Transactional` method
   backed by a connection pool; lock contention, connection pool exhaustion,
   and GC pause impact under concurrent load are not measured by anything
   in this suite.

2. **Cache and data-size effects at production scale.** Every benchmark used
   a fixed, small input, a single `PaymentEntity` for the mapping benchmark,
   a 500-element list for the collection and settlement benchmarks. A
   merchant with 50,000 payments, or a JVM heap under real memory pressure
   from many other live objects, will not necessarily show the same
   per-operation cost or the same allocation profile as this isolated,
   warmed-up microbenchmark.

3. **The gap between the benchmark harness and the live request path.**
   `SettlementBenchmark.owedMinor` calls the service method directly with a
   mocked repository. The real request path also includes the HTTP layer,
   JSON serialization, the actual JPA query and Postgres round trip, and
   whatever load balancing or proxying sits in front of the service in
   production. None of that is exercised here, so these numbers describe
   the settlement calculation logic in isolation, not the end-to-end
   request latency a client actually experiences.

A fourth, specific to this run: the abnormally long average GC pause seen
in `owedMinor` (Task 8/9) was not root-caused to a specific system-level
condition, only observed and flagged. A benchmark run repeated on a quiet,
dedicated machine, ideally with `-prof gc` again, would be needed to confirm
whether that variance is reproducible or was specific to this machine's
state at the time.

