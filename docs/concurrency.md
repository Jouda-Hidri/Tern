# Coroutines, and why not virtual threads

**Decision: keep coroutines, run on Java 21.** Migrating deletes a lot of machinery and buys no
throughput. Worth doing one day for simplicity, never for speed.

Coroutines suspend - the task pauses and hands its thread back. Virtual threads keep the thread
model and make threads cheap. Same goal, one solved in the language, one in the runtime.

## Coroutine capacity for blocking work

Blocking work runs on a dispatcher with a fixed parallelism. `Dispatchers.IO` defaults to **64**.
That number is the capacity: throughput is parallelism divided by how long the call blocks.

Measured with a `Thread.sleep(500)` endpoint at concurrency 500:

| `limitedParallelism(N)` | rps | N / 0.5s | OS threads |
| --- | --- | --- | --- |
| 16 | 30 | 32 | 118 |
| **64** (default) | 118 | 128 | 226 |
| 128 | 236 | 256 | 337 |
| 256 | 473 | 512 | 497 |
| 500 | 930 | 1000 | 724 |

Linear, at ~93% of theoretical, and **each unit of parallelism costs one OS thread**.

Virtual threads reached the same 930 rps with **27** threads. So the ceiling is tunable and
coroutines can match virtual threads on throughput. What they cannot match is the thread cost.

## Where that becomes fatal

Ration the threads - `pids_limit: 150` on the container, same 500 concurrent requests:

| Config | Result |
| --- | --- |
| Coroutines, `limitedParallelism(500)` | **81 x `OutOfMemoryError: unable to create native thread`**, container unhealthy, no responses |
| Virtual threads | **932 rps, 0 failures**, 27 threads, healthy |

Suspension frees coroutines from the servlet pool, not from the thread a blocked JDBC call sits
on. Where the thread budget is rationed and blocking concurrency is high, that is the difference
between serving and falling over.

## Two things that are not the differentiator

**The servlet pool.** With `server.tomcat.threads.max=50`, plain blocking code manages 90 rps.
Coroutines manage 624 and virtual threads 685 - a `suspend` controller releases the servlet thread
while it waits, so both escape the pool. Only non-suspending blocking code is capped by it.

**Raw speed.** At `cpu: "1"` and concurrency 60 against the real endpoints, virtual threads
measured 49 rps against platform threads' 51 on the same build. No difference. A 3x gap against
the Boot 2.7 build was Spring Boot's cost, not the thread model - visible only by running Boot 3.3
with virtual threads switched off.

## Why none of it applies to Tern

Every `Dispatchers.IO` here wraps a JDBC call and nothing else. The detector and the gRPC stub
suspend rather than block, so they hold no thread at all.

The database pool is **10 connections**. That is the hard ceiling on concurrent blocking
operations - the thread budget would have to fall below ~40 before it mattered. Hikari connection
usage averages ~15ms, so by Little's law you would need ~4,300 rps to have even 64 threads blocked
at once.

## What a migration would cost and delete

Spiked and verified: 54/54 tests on Boot 3.3.5 / Java 21, virtual threads confirmed serving
(`VirtualThread[#50,tomcat-handler-2] virtual=true`). About thirty minutes.

Boot 2.7 to 3.3.5, Kotlin 1.7.10 to 1.9.25, Java 11 to 21, `javax` to `jakarta` (7 imports), grpc
starter 2.13.1 to 3.1.0, `spring.threads.virtual.enabled: true`.

Three things only surfaced by running it:

- `protoc-gen-grpc-kotlin` dropped its `jdk7` classifier; 1.4.x publishes `jdk8`.
- Removing the unused JPA starter broke repository discovery - Spring Data JDBC's
  auto-configuration was arriving through it. `spring-boot-starter-data-jdbc` is the fix.
- Flyway 10 split database support into modules; without `flyway-database-postgresql` it reports
  `Unsupported Database: PostgreSQL 14.24`.

It would delete roughly 28 of 33 coroutine constructs, plus `kotlinx-coroutines-reactor`,
`kotlinx-coroutines-slf4j` and Reactor itself. Two things get worse: **`Flow` has no Java
counterpart**, so the streaming read path would go back to a materialised list, and **cancellation
degrades** - `CancellationException` propagates structurally, thread interruption does not.

## An unrelated capacity limit worth knowing

At `cpu: "1"`, 60 concurrent `GET /` requests fail 74-98% of the time in every configuration -
all `504`, p50 pinned at the 2s `ANTARCTIC_DEADLINE`. `deployment/artic.yaml` sets `cpu: "1"` and
`maxReplicas: 1`, so the cluster behaves this way too. More CPU or more replicas fixes it;
threading model does not.

## Reproducing

````
docker compose --profile translate up -d --build
./benchmark/load.sh coroutines
````

The blocking-endpoint runs above needed a throwaway `GET /slow` and are not in the code. If you
repeat the Boot 3 comparison, measure the same build with `spring.threads.virtual.enabled=false`
as well, or you will attribute Spring Boot's cost to virtual threads.
