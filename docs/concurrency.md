# Coroutines, and why virtual threads are switched off

The stack is Spring Boot 3.3, Kotlin 1.9, Java 21 - so virtual threads are one property away.
`spring.threads.virtual.enabled` is deliberately not set. This is why, measured rather than
argued.

## The property would not reach the code that blocks

Enabling it and looking at which thread runs what:

````
artic HTTP handler       tomcat-handler-15          <- virtual thread
antarctic gRPC handler   DefaultDispatcher-worker-8 <- unchanged
````

It reconfigures Tomcat. It does not reconfigure the gRPC server, whose handlers grpc-kotlin runs
on `Dispatchers.Default`. **Every `Dispatchers.IO` in this codebase is in antarctic**, so the
property does not touch a single blocking call:

````
withContext(Dispatchers.IO) { db.save(...) }              AntarcticService
withContext(Dispatchers.IO) { db.findWithoutLanguage() }  LanguageBackfill
withContext(Dispatchers.IO) { jdbc.batchUpdate(...) }     LanguageBackfill
flowOn(Dispatchers.IO)                                    AntarcticService
````

Artic gains little either: its controllers are `suspend`, so they already release the servlet
thread while waiting. The property would change which threads serve HTTP and nothing else.

## Coroutine capacity for blocking work

Blocking work runs on a dispatcher with fixed parallelism. `Dispatchers.IO` defaults to **64**.
That number is the capacity: throughput is parallelism divided by how long the call blocks.

Measured with a `Thread.sleep(500)` endpoint at concurrency 500:

| `limitedParallelism(N)` | rps | N / 0.5s | OS threads |
| --- | --- | --- | --- |
| 16 | 30 | 32 | 118 |
| **64** (default) | 118 | 128 | 226 |
| 128 | 236 | 256 | 337 |
| 256 | 473 | 512 | 497 |
| 500 | 930 | 1000 | 724 |

Linear at ~93% of theoretical, and **each unit of parallelism costs one OS thread**. Virtual
threads reached the same 930 rps with **27** threads.

So the ceiling is tunable and coroutines match virtual threads on throughput. What they cannot
match is the thread cost.

## Where the thread cost becomes fatal

Ration the threads - `pids_limit: 150`, same 500 concurrent requests:

| Config | Result |
| --- | --- |
| Coroutines, `limitedParallelism(500)` | **81 x `OutOfMemoryError: unable to create native thread`**, container unhealthy, no responses |
| Virtual threads | **932 rps, 0 failures**, 27 threads, healthy |

Suspension frees coroutines from the servlet pool, not from the thread a blocked JDBC call sits
on. Where threads are rationed and blocking concurrency is high, that is the difference between
serving and falling over.

## Two things that are not the differentiator

**The servlet pool.** With `server.tomcat.threads.max=50`: plain blocking code manages 90 rps,
coroutines 624, virtual threads 685. A `suspend` controller releases the servlet thread, so both
escape it. Only non-suspending blocking code is capped.

**Raw speed.** At `cpu: "1"` and concurrency 60 against the real endpoints, virtual threads
measured 49 rps against platform threads' 51 on the same build. A 3x gap against the old Boot 2.7
build was Spring Boot's cost, not the thread model - visible only by running Boot 3.3 with the
property switched off.

## Why none of it bites here

The database pool is **10 connections**. That is the hard ceiling on concurrent blocking
operations, so the thread budget would have to fall below ~40 before it mattered. Hikari
connection usage averages ~15ms, so by Little's law you would need ~4,300 rps to have even 64
threads blocked at once.

The detector and the gRPC stub suspend rather than block, and hold no thread at all.

## What turning it on would take

Not the property on its own. It would need the gRPC server moved onto virtual threads too, and
then the `Dispatchers.IO` wrappers removed - left in place on a virtual-thread runtime they
reimpose a 64-wide cap that throttles ~930 rps to ~118. All or nothing per call site.

That would delete roughly 28 of 33 coroutine constructs, plus `kotlinx-coroutines-reactor` and
`kotlinx-coroutines-slf4j`. Two things would get worse: **`Flow` has no Java counterpart**, so the
streaming read path would return to a materialised list, and **cancellation degrades** -
`CancellationException` propagates structurally, thread interruption does not.

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

The blocking-endpoint runs above needed a throwaway `GET /slow` and are not in the code.
