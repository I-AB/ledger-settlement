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