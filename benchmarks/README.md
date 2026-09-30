# Benchmarks Module

JMH microbenchmarks for the ledger settlement service.

## Build and run

From the repository root:

    mvn -pl benchmarks -am clean package -DskipTests
    java -jar benchmarks/target/benchmarks.jar

Prerequisite: Postgres must be reachable at localhost:5433 before running,
since `SettlementBenchmark` depends on `FeeScheduleLookup`'s static
initializer (see docs/benchmarks.md, Task 2, for why).

To reproduce the full documented run, including allocation profiling:

    java -jar benchmarks/target/benchmarks.jar -rf json -rff results.json
    java -jar benchmarks/target/benchmarks.jar -prof gc -rf json -rff results-gc.json

Each of these takes roughly 30 minutes (3 forks x 15 iterations x 4
benchmark methods). See docs/benchmarks.md for full results, methodology,
and analysis.