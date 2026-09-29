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


## Task 3: current pooling

This service uses plain synchronous Spring MVC with no custom `@Async` methods and no `ThreadPoolTaskExecutor` bean. Every request to `GET /payments/settlement` is handled directly by one of Tomcat's own embedded connector worker threads (the `http-nio-8080-exec-*` threads), not by a separately-managed Spring executor.

No `server.tomcat.threads.*` properties are overridden in `application.yml`, so the service runs on Spring Boot's Tomcat defaults, verified empirically with `jcmd <pid> Thread.print` rather than assumed from documentation alone:

| Setting | Value | How verified |
|---|---|---|
| Core / min-spare threads | 10 | Counted `http-nio-8080-exec-*` threads via `jcmd` with the service idle, zero load |
| Maximum threads | 200 | Counted the same threads via `jcmd` while k6 drove 200 concurrent VUs against the settlement endpoint; count held steady at exactly 200 |
| Queue capacity (accept-count) | 100 (documented Tomcat/Spring Boot default) | Not independently measured; would require deliberately exceeding 300 concurrent connections to observe queuing/rejection behavior, which was out of scope for this check |

The 200-thread ceiling matches what the baseline load test already showed indirectly: a clean 200-VU run completes with 0% failures, but running a JVM thread dump mid-load (which briefly pauses the JVM at a safepoint) pushed a run at the same load to 0.36% failures and a 4.99s max latency, consistent with the pool having zero headroom at 200 concurrent requests.


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

Result: throughput and latency got worse across every percentile after enabling virtual threads. Not explained yet, per the lab instructions.