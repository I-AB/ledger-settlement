# Virtual Threads and Pinning - Lab Notes

## Baseline load test (pre-migration)

Endpoint under test: GET /payments/settlement?merchantId=MR-4471

Tool: k6, 200 VUs, 60s duration, run from loadtest/baseline.js.

| Metric | Value |
|---|---|
| Throughput | 701.73 req/s |
| p50 latency | 267.4 ms |
| p90 latency | 389.83 ms |
| p95 latency | 455.02 ms |
| p99 latency | 582.42 ms |
| Max latency | 1.35 s |
| Failed checks | 0 / 42254 (0.00%) |

Note: an earlier run at the same 200-VU load showed 50 failed checks (0.14%) and a 3.97s max latency spike, consistent with the request queue briefly saturating against Tomcat's default platform-thread pool (max 200 threads) at exactly 200 concurrent VUs. The table above uses the clean, repeatable rerun as the official baseline.

Current pooling (to confirm in Task 3): Spring Boot's embedded Tomcat, default server.tomcat.threads.max = 200, no explicit override in application.yml.

# Virtual Threads and Pinning - Lab Notes

## Task 1: branch and toolchain

Created a real GitHub repository, I-AB/ledger-settlement, since the Module 0 codebase only existed as a local, unpushed git history. Initialized git in the existing sjv-l0-4/ledger-settlement folder, committed the Lab 4 code as the initial commit, and pushed it with gh repo create --public --source=. --remote=origin --push.

Created the branch: git checkout -b feature/virtual-threads.

Confirmed the toolchain, after fixing a PATH issue where a stray JDK 27 install was shadowing the intended JDK 25 (JDK 27 was uninstalled/disabled): 

java -version
openjdk version "25.0.4" 2026-07-21 LTS
OpenJDK Runtime Environment Temurin-25.0.4+7 (build 25.0.4+7-LTS)
OpenJDK 64-Bit Server VM Temurin-25.0.4+7 (build 25.0.4+7-LTS, mixed mode, sharing)

mvn -v
Apache Maven 3.9.16
Java version: 25.0.4, vendor: Eclipse Adoptium


## Task 2: baseline load test (pre-migration)

Endpoint under test: GET /payments/settlement?merchantId=MR-4471

Tool: k6, 200 VUs, 60s duration, run from loadtest/baseline.js.

| Metric | Value |
|---|---|
| Throughput | 701.73 req/s |
| p50 latency | 267.4 ms |
| p90 latency | 389.83 ms |
| p95 latency | 455.02 ms |
| p99 latency | 582.42 ms |
| Max latency | 1.35 s |
| Failed checks | 0 / 42254 (0.00%) |

Note: an earlier run at the same 200-VU load showed 50 failed checks (0.14%) and a 3.97s max latency spike, consistent with the request queue briefly saturating against Tomcat's default platform-thread pool (max 200 threads) at exactly 200 concurrent VUs. The table above uses the clean, repeatable rerun as the official baseline.

## Task 3: current pooling

This service uses plain synchronous Spring MVC with no custom @Async methods and no ThreadPoolTaskExecutor bean. Every request to GET /payments/settlement is handled directly by one of Tomcat's own embedded connector worker threads (the http-nio-8080-exec-* threads), not by a separately-managed Spring executor.

No server.tomcat.threads.* properties are overridden in application.yml, so the service runs on Spring Boot's Tomcat defaults, verified empirically with jcmd <pid> Thread.print rather than assumed from documentation alone:

| Setting | Value | How verified |
|---|---|---|
| Core / min-spare threads | 10 | Counted http-nio-8080-exec-* threads via jcmd with the service idle, zero load |
| Maximum threads | 200 | Counted the same threads via jcmd while k6 drove 200 concurrent VUs against the settlement endpoint; count held steady at exactly 200 |
| Queue capacity (accept-count) | 100 (documented Tomcat/Spring Boot default) | Not independently measured; would require deliberately exceeding 300 concurrent connections to observe queuing/rejection behavior, which was out of scope for this check |

The 200-thread ceiling matches what the baseline load test already showed indirectly: a clean 200-VU run completes with 0% failures, but running a JVM thread dump mid-load (which briefly pauses the JVM at a safepoint) pushed a run at the same load to 0.36% failures and a 4.99s max latency, consistent with the pool having zero headroom at 200 concurrent requests.

## Task 4: migrating to virtual threads

Added one property to application.yml:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

The lab's scenario also asks to "replace the application executor bean with one built from Executors.newVirtualThreadPerTaskExecutor()," but this codebase has no custom executor bean at all: no @Async usage, no hand-written TaskExecutor. Since Spring Boot 3.2+ (this project is on 4.1.1), the single property above already covers both halves of the migration automatically: it switches Tomcat's connector to hand requests to virtual threads, and it switches Spring's default applicationTaskExecutor to a virtual-thread-per-task executor. Writing a manual executor bean here would have been redundant, since there was nothing custom to replace.

Verified empirically after restarting the service: with zero load, jcmd Thread.print showed 0 threads matching http-nio-8080-exec-* (the old platform-thread pool naming no longer applies) and 4 threads matching VirtualThread, confirming requests are now serviced by virtual threads.

## Task 5: post-migration load test comparison

Same test as the baseline: k6, 200 VUs, 60s, against GET /payments/settlement?merchantId=MR-4471, run immediately after enabling spring.threads.virtual.enabled=true and restarting the service.

| Metric | Baseline (platform threads) | Post-migration (virtual threads) |
|---|---|---|
| Throughput | 701.73 req/s | 626.38 req/s |
| p50 latency | 267.4 ms | 291.53 ms |
| p90 latency | 389.83 ms | 419.82 ms |
| p95 latency | 455.02 ms | 526.14 ms |
| p99 latency | 582.42 ms | 797.95 ms |
| Max latency | 1.35 s | 1.96 s |
| Failed checks | 0.00% | 0.00% |

Result: throughput and latency got worse across every percentile after enabling virtual threads. Not explained at this point, per the lab instructions; revisited in Task 10.

## Task 6: planting a pinning defect

Added FeeScheduleLookup, a new class simulating a downstream client's lookup table, loaded once via a blocking call inside a static initializer:

```java
final class FeeScheduleLookup {
    static final int TABLE_VERSION;
    static {
        TABLE_VERSION = fetchTable();
    }
    private static int fetchTable() {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5433/ledger", "ledger", "ledger")) {
            Thread.sleep(300);
            return 1;
        } catch (SQLException | InterruptedException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
```

Wired it into the request path by reading FeeScheduleLookup.TABLE_VERSION at the top of SettlementService.owedMinor, so the class loads (and its static initializer runs) the first time any settlement request is served after a cold start.

Restarted the service cold and confirmed the defect is live with a timed sanity check: the first request after restart took 1721.6 ms; the second, once the class was already loaded, took 36.9 ms.

## Task 7: capturing the pinning with JFR

Installed JDK Mission Control (jdk.java.net/jmc) to read recordings, since it was not already on the machine.

Restarted the service cold (new PID each time, since the static initializer only runs once per JVM lifetime), then, before sending any traffic: 

jcmd 21220 JFR.start name=pin settings=profile filename=pinned.jfr

jcmd 21220 JFR.stop name=pin

## Task 8: captured pinned event

Recording: pinned.jfr, captured via jcmd JFR.start/JFR.stop while a fresh, cold-start service was hit with 200 concurrent VUs for 20s (k6), before FeeScheduleLookup had ever been touched.

Two related event shapes appeared, both for the same root cause:

1. The one virtual thread that actually ran the static initializer:
    - Duration: 325.002 ms
    - Pinned Reason: "VM call to com.ledger.FeeScheduleLookup.<clinit> on stack"
    - Stack trace: void java.lang.VirtualThread.sleepNanos(long)
      void java.lang.Thread.sleepNanos(long)
      void java.lang.Thread.sleep(long)
      int com.ledger.FeeScheduleLookup.fetchTable()
      void com.ledger.FeeScheduleLookup.<clinit>()
      long com.ledger.SettlementService.owedMinor(String)
2. Roughly 199 other virtual threads that arrived while the first was still initializing:
    - Duration: 372.022 ms, 370.659 ms, 370.554 ms (and more)
    - Pinned Reason: "Waited for initialization of com.ledger.FeeScheduleLookup by another thread"

The frame that cannot unmount is FeeScheduleLookup.<clinit>, the class's static initializer. The call blocking inside it is Thread.sleep(300), invoked from fetchTable(). HotSpot's class initialization is guarded by an internal, JVM-level lock that does not support the virtual thread mount/unmount protocol, so any thread inside that lock, whether actively running the initializer or waiting for another thread to finish it, is pinned to its carrier for the whole duration, regardless of what kind of work the initializer does.

Advice written before Java 24 says to replace synchronized blocks with a java.util.concurrent lock such as ReentrantLock, because a virtual thread blocking while holding a monitor acquired via synchronized could not unmount, while java-level locks could. That advice buys nothing here: there is no synchronized statement in this code to swap out. The pinning comes from the JVM's own internal class-initialization lock, which every class implicitly has and which no amount of rewriting application-level locking can bypass.

Bonus, unplanted finding: the same load also produced "Waited for initialization of org.hibernate.grammars.hql.HqlLexer by another thread" events, since Hibernate lazily initializes its own query-parsing classes on first use. This confirms the defect pattern is general: any class touched for the first time by many concurrent virtual threads at once can pin, not just the one deliberately planted here.

## Task 9: the fix

Chose the eager-initialization strategy from the two options in the lab (eager load at startup vs. bounded platform-thread executor), since it eliminates the race entirely rather than managing it.

Added FeeScheduleWarmup, a Spring @Component with a @PostConstruct method that reads FeeScheduleLookup.TABLE_VERSION. Spring runs @PostConstruct methods while constructing singleton beans, which happens before the embedded Tomcat server starts accepting connections. So by the time any request can arrive, FeeScheduleLookup has already finished its static initializer, on the main startup thread, with no concurrent traffic to race against.

SettlementService.owedMinor and FeeScheduleLookup itself were left unchanged: the fix only adds a startup-time trigger, keeping the result semantics identical, per the lab's requirement.

Added FeeScheduleWarmupTest, a @SpringBootTest that asserts FeeScheduleLookup.TABLE_VERSION is already set the moment the Spring application context finishes starting, proving initialization happens at startup and not lazily on the first request. Ran alongside the existing PaymentControllerIT: mvn test reports Tests run: 2, Failures: 0, Errors: 0, BUILD SUCCESS.

## Task 10: post-fix verification

Recording: fixed.jfr, captured the same way as pinned.jfr (jcmd JFR.start/JFR.stop around a fresh cold-start service under 200-VU load), after adding FeeScheduleWarmup.

Result for the planted defect: zero jdk.VirtualThreadPinned events reference com.ledger.FeeScheduleLookup. The fix works: eagerly initializing the class in a @PostConstruct method during application startup, before Tomcat accepts connections, removes the race entirely.

Separate finding, out of scope for this fix: 21 jdk.VirtualThreadPinned events remain in fixed.jfr, all reporting "Waited for initialization of org.hibernate.grammars.hql.HqlLexer by another thread" (or HqlParser), with stack traces rooted in Hibernate's own query-parsing internals (HqlParseTreeBuilder.buildHqlLexer, StandardHqlTranslator.parseHql, QueryEngine.interpretHql). This is the same defect pattern as FeeScheduleLookup, a class lazily initialized under concurrent virtual-thread load, but it lives inside Hibernate itself, not in this project's code, and was not part of the planted defect this lab asked us to fix.

Final load test comparison, same k6 script and load profile throughout:

| Metric | Baseline (platform threads) | Virtual threads, defect present | Virtual threads, defect fixed |
|---|---|---|---|
| Throughput | 701.73 req/s | 626.38 req/s | 615.51 req/s |
| p50 latency | 267.4 ms | 291.53 ms | 292.97 ms |
| p90 latency | 389.83 ms | 419.82 ms | 430.62 ms |
| p95 latency | 455.02 ms | 526.14 ms | 539.88 ms |
| p99 latency | 582.42 ms | 797.95 ms | 723.7 ms |
| Max latency | 1.35 s | 1.96 s | 3.3 s |
| Failed checks | 0.00% | 0.00% | 0.09% (35/37062) |

Fixing the planted FeeScheduleLookup defect did not bring throughput or latency back to the platform-thread baseline, and the max latency and failure rate are still worse than the platform-thread baseline. This is consistent with the remaining Hibernate HqlLexer/HqlParser pinning still degrading the cold start window, and with this workload's real bottleneck likely being elsewhere (a single Postgres connection pool of limited size serving 200 concurrent virtual threads), not the thread model itself. Virtual threads remove a hard concurrency ceiling (200 platform threads) but do not automatically make a downstream-resource-bound workload faster.