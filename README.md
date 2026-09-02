# Tern

Tern stores short text messages and reports how many of them are written in each language.

`POST /` stores a message, `GET /` lists them back, `GET /stats` counts them by language. The
language is never supplied by the caller: the service asks a detector what the message was
written in and stores that alongside it, so `/stats` fills itself in.

The service is mid-migration, which is why there are two of it. `artic` is the new version and
takes the traffic; `antarctic` is the legacy version and still owns the database. Artic reaches
it over gRPC rather than HTTP, so the hop between them is a binary call on one persistent
connection instead of another REST service in the chain. Both roles run from the same image.

````
    you ──HTTP──▶ artic ──gRPC──▶ antarctic ──▶ postgres
                                      │
                                      └──▶ libretranslate   detects the language before the
                                                            write, so it is stored once (optional)
````

| Service | Port | What it is |
| --- | --- | --- |
| `artic` | 8080 | The new version. REST API, Kotlin + Spring Boot |
| `antarctic` | 9090 | The legacy version. gRPC service - the same image, in its other role |
| `dbpostgresql` | 5432 | Postgres, schema managed by Flyway |
| `libretranslate` | 5050 | Language detection. Behind a profile, see below |

Three things are documented separately: [Istio, metrics and dashboards](docs/observability.md),
[CI/CD](docs/ci-cd.md), and [why virtual threads are switched off](docs/concurrency.md).

## Quick start

You only need **Docker**.

````
docker compose up -d --build                        # the stack
docker compose --profile translate up -d --build    # ...plus language detection
LOG_LEVEL=DEBUG docker compose up -d --build        # ...with the full trace
````

When the command returns the stack is genuinely up:

````
docker compose ps
````

Stop everything with `docker compose --profile translate down`, or add `-v` to discard the
database and start clean.

## Using it

### `POST /` - store a message

Answers `201` once antarctic has confirmed the write. If that call times out we do not know
whether it landed, so the answer is `202` - accepted, outcome unconfirmed - rather than claiming
a resource was created or reporting a failure that may not have happened.

````
curl -i -X POST localhost:8080/ \
  -H 'Content-Type: application/json' \
  -d '{"text":"Bonjour mon ami, comment vas-tu"}'
````

Send your own id with `-H 'X-Request-Id: my-id'` and it is used instead of a generated one.

````
HTTP/1.1 201
X-Request-Id: 5f2a91c4

{"text":"Bonjour mon ami, comment vas-tu","language":"fr"}
````

Antarctic detects the language before it inserts the row, so the message is written once, with
its language already on it. That happens on this path: a `POST` waits for the detector, which
answers in single-digit milliseconds when it is up and is bounded by `TRANSLATE_TIMEOUT` when it
is not - which is why the language comes back in the response rather than appearing later. It is
`unknown` when the detector could not name one, and on a `202`, where nothing is confirmed.
Short inputs are hard to detect: a whole sentence gives far better results than `"Bonjour!"`.

### `GET /` - list every message

````
curl -s localhost:8080/ | python3 -m json.tool
````

### `GET /stats` - counts by language

Counts by language across every message. A message counts under its language as soon as the
`POST` returns - the language is written with it, not filled in afterwards.

````
curl -s localhost:8080/stats
````

````
{"total":3,"byLanguage":{"en":1,"es":1,"fr":1}}
````

`unknown` counts messages the detector could not name - it was unavailable, or it had nothing
confident to say. It always sorts last.

The bundled detector loads `en`, `fr`, `ru` and `es` only, so anything else is reported as the
closest of those rather than correctly.

### Errors

Every error carries the request id, so a report can be tied straight back to the logs.

| Request | Answer |
| --- | --- |
| `{"text":"   "}` | `400` `text must not be blank` |
| `{"text":"<over 1000 chars>"}` | `400` `text must be at most 1000 characters` |
| `{"nope":1}` or invalid JSON | `400` `Request body is malformed or missing required fields` |
| `GET /` while antarctic is down | `503` `Antarctic is unavailable`, or `504` `Antarctic is deadline exceeded` |
| `POST /` while antarctic is down | `202`, then `503` - see below |

````
{"status":400,"message":"text must not be blank","requestId":"8f0affb2"}
````

`src/main/resources/requests.http` has all of these ready to run from an IDE.

### Talking to gRPC directly

Server reflection is on, so no `.proto` file is needed:

````
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext -d '{}' localhost:9090 tern.grpc.TernService/GetMessage
grpcurl -plaintext -d '{"text":"via grpc"}' localhost:9090 tern.grpc.TernService/SaveMessage
````

No grpcurl installed? `docker run --rm fullstorydev/grpcurl -plaintext host.docker.internal:9090 list`

## Following a request in the logs

````
docker compose logs -f                                  # live, interleaved across services
docker compose logs | grep 5f2a91c4 | sort -t'|' -k2    # one request, both services
````

By default, each service logs the point a request enters it plus what it is doing:

````
artic     [5f2a91c4] HTTP --> POST / - request received
artic     [5f2a91c4] POST / - Posting message: Bonjour mon ami
artic     [5f2a91c4] Artic - Request message: Bonjour mon ami
antarctic [5f2a91c4] gRPC --> tern.grpc.TernService/SaveMessage - call received
antarctic [5f2a91c4] Antarctic - Request messages
````

`LOG_LEVEL=DEBUG` adds the other side of each hop, with statuses and timings:

````
INFO  artic     [5f2a91c4] HTTP --> POST / - request received
DEBUG artic     [5f2a91c4] gRPC --> tern.grpc.TernService/SaveMessage - calling antarctic
INFO  antarctic [5f2a91c4] gRPC --> tern.grpc.TernService/SaveMessage - call received
DEBUG antarctic [5f2a91c4] gRPC <-- tern.grpc.TernService/SaveMessage - OK in 368 ms
DEBUG artic     [5f2a91c4] gRPC <-- tern.grpc.TernService/SaveMessage - OK in 716 ms
DEBUG artic     [5f2a91c4] HTTP <-- POST / - responded 201 in 1635 ms
````

## When things break

The detector is allowed to be down - it cannot stop a message being stored. It is on the write
path now, though, so it is no longer free: a `POST` waits `TRANSLATE_TIMEOUT` for it before
giving up and storing the message without a language. Antarctic is different: without it there
is nothing to store into.

| Stop this | What happens |
| --- | --- |
| `docker compose stop libretranslate` | `201` still, one `TRANSLATE_TIMEOUT` slower; the message counts as `unknown` in `/stats` |
| `docker compose stop antarctic` | `POST` answers `202` then `503`, `GET` answers `504` then `503` |

Which code comes back depends on what gRPC knows. While the call still has to wait out
`ANTARCTIC_DEADLINE`, the write may or may not have landed - `POST` answers `202` and `GET`
answers `504`. Once gRPC has marked the connection dead it fails in milliseconds, and the
request definitely did not reach the database, so both answer `503`. Under Kubernetes, Envoy
knows immediately that no antarctic endpoint is healthy, so `503` starts from the first call.

Readiness deliberately stays `UP` through all of it. Artic still works, and failing readiness
would pull it out of the load balancer too - turning one outage into two.

After antarctic returns, the next request or two may still fail while gRPC backs off; it
clears once antarctic finishes starting.

## Health and metrics

````
curl localhost:8080/actuator/health/liveness    # what the k8s livenessProbe hits
curl localhost:8080/actuator/health/readiness   # what the readinessProbe hits
curl localhost:8080/actuator/health             # db, disk, liveness and readiness state
curl localhost:8080/actuator/prometheus         # micrometer metrics
````

Nothing scrapes this under docker-compose. Under Kubernetes the pods carry
`prometheus.io/scrape` annotations, so Istio's Prometheus collects these alongside the mesh
metrics - see [docs/observability.md](docs/observability.md).

## Configuration

Everything is defaulted for Kubernetes and overridden by compose, so neither needs editing.

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_HOST` / `_DB` / `_USER` / `_PASSWORD` | - / `postgres` | Database connection |
| `ANTARCTIC_TARGET` | `antarctic.default.svc.cluster.local:30000` | Where artic finds antarctic |
| `ANTARCTIC_DEADLINE` | `2s` | Bounds every gRPC call, so an unreachable antarctic fails fast |
| `TRANSLATE_URL` | `http://libretranslate:5000` | Detector, read by antarctic. Empty means no detector, and the call is skipped rather than attempted |
| `TRANSLATE_TIMEOUT` | `1s` | What a `POST` waits for the detector before storing without a language. Warm calls take milliseconds; the first after a restart pays connection setup, so a tighter value stores the first message or two as `unknown` for good. Must be shorter than `ANTARCTIC_DEADLINE` or the application refuses to start: detection runs inside the gRPC call, so a longer timeout means the deadline cancels the write instead of delaying it |
| `LOG_LEVEL` | `INFO` | `DEBUG` for the full trace |
| `SERVICE_NAME` | `tern` | Which role this container is playing, shown in every log line |

## Tests

````
mvn verify
````

Needs Docker running and **JDK 21**, which is what CI and the container image both use. If
`mvn -v` reports something else:

````
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn verify
````

Unit tests cover the gRPC adapter's error translation and the HTTP status mapping.
`ArticServiceTest` runs a real gRPC server over the in-process transport, and
`LanguageDetectorTest` runs the detector against a MockWebServer to check what it does on
timeouts, 5xx and malformed bodies. `MessageApiIntegrationTest` covers the whole path - HTTP
into artic, gRPC to antarctic, a Flyway-migrated Postgres in a Testcontainer, and back.

`EndToEndTest` covers the table in "When things break". Artic's channel goes through a proxy the
test can make hang or refuse, which is what makes an antarctic outage expressible at all when
both roles run in the same JVM, and it pins the answers each mode produces - `202`/`504` while
the deadline is being waited out, `503` once the connection is known to be dead.
`TernConfigurationTest` checks that a detector timeout longer than the gRPC deadline is refused
at startup, since that combination loses messages rather than delaying them.

## Running it on Kubernetes with Minikube

The same thing on a local cluster. Make sure you have Minikube installed.

````
minikube start --driver=docker --cpus=4 --memory=6144
minikube addons enable metrics-server
eval $(minikube docker-env)
docker build -t tern .
kubectl apply -f deployment/postgres.yaml -f deployment/antarctic.yaml -f deployment/artic.yaml
````

Both deployments declare liveness and readiness probes against the actuator endpoints above, and
an initContainer that waits for Postgres - Kubernetes has no `depends_on`, so without it the app
starts before the database accepts connections and crash-loops until it wins the race.

````
kubectl port-forward svc/artic 8080:8080

curl -d '{"text":"some-text"}' -H "Content-Type: application/json" -X POST localhost:8080/
curl localhost:8080/
````

Language detection is not deployed here, so messages stay under `unknown` in `/stats`.
`deployment/antarctic.yaml` sets `TRANSLATE_URL` to the empty string to say so explicitly, which
means the call is not attempted at all. Leaving the compose default in place would not degrade
gracefully: the name does not resolve inside the cluster and the connection hangs rather than
failing fast, so every write would wait out `TRANSLATE_TIMEOUT` for a service that is never
coming. Point it at a reachable detector to turn detection on - and give antarctic egress to it
if your mesh restricts outbound traffic.

Both deployments also carry a HorizontalPodAutoscaler targeting 50% CPU, capped at
`maxReplicas: 1` so a laptop cluster stays small. Raise it to see it scale.

For the service mesh, Prometheus and Grafana on top of this, see
[docs/observability.md](docs/observability.md).
