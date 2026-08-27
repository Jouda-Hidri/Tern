# Tern

The Arctic tern holds the record for the longest migration route of any bird, traveling from the Arctic to the Antarctic and back again every year. The Antarctic tern is a species of tern that is native to the Antarctic region.    
We want to migrate a service (Tern service) from legacy (Antarctic version) to a new version (Arctic version). We want Arctic to be receiving the traffic and synch with Antarctic, without falling in the issue of cascading HTTP.    
We want to use Istio to maintain 2 deployments of the same app.    
We can also simply use docker-compose, if we do not want to start locally Minikube.    
The 2 services communicate using gRPC. On the gRPC callback, we make a call to Tapi using WebClient. Tapi exposes a a very large CSV file that we want to read using streaming, to avoid the issue of loading a large data in memory.

## Setup

Make sure you have Docker and maven 3.6.3 installed. Build on JDK 11-17: the Kotlin 1.7.10
compiler cannot parse the version string of newer JDKs, which is why CI pins Temurin 17.

### Option 1: using docker-compose

docker-compose up --build

Compose mirrors the Kubernetes topology: the same image runs twice, as `artic` (REST, port 8080)
and as `antarctic` (gRPC, port 9090), alongside postgres. The Dockerfile is multi-stage, so a
clean checkout is enough - no `mvn package` beforehand.

Each service is gated on the one it depends on being *healthy*, not merely started, so the stack
comes up in order on a cold machine.

#### Following a request end to end

Every log line carries `[service] [requestId]`, and the id is propagated from the HTTP request
through the gRPC hop, so a single request can be followed across both services:

````
docker compose logs -f                    # live, interleaved across services
docker compose logs | grep b5483e6a       # one request, both services
curl -i -X POST localhost:8080/ -d '{"text":"hi"}' -H 'Content-Type: application/json'
                                          # response carries X-Request-Id: b5483e6a
````

By default each service logs exactly one line per request - the point it enters - so the shape of
a request is visible without the logs becoming unreadable:

````
artic     [b5483e6a] HTTP --> POST / - request received
antarctic [b5483e6a] gRPC --> tern.grpc.TernService/SaveMessage - call received
````

Probe traffic to `/actuator` is excluded, since kubelet and compose hit it every few seconds.
Failures are never silent at this level: they are logged by the exception handler on each side.

`LOG_LEVEL=DEBUG` turns the same request into the full hop-by-hop trace, with statuses and timings:

````
LOG_LEVEL=DEBUG docker compose up -d
````

````
INFO  artic     [b5483e6a] HTTP --> POST / - request received
DEBUG artic     [b5483e6a] POST / - Posting message: hi
DEBUG artic     [b5483e6a] Artic - Saving message: hi
DEBUG artic     [b5483e6a] gRPC --> tern.grpc.TernService/SaveMessage - calling antarctic
INFO  antarctic [b5483e6a] gRPC --> tern.grpc.TernService/SaveMessage - call received
DEBUG antarctic [b5483e6a] Antarctic - Saved message 2d10ae23-... to the database
DEBUG antarctic [b5483e6a] gRPC <-- tern.grpc.TernService/SaveMessage - OK in 93 ms
DEBUG artic     [b5483e6a] gRPC <-- tern.grpc.TernService/SaveMessage - OK in 224 ms
DEBUG artic     [b5483e6a] Artic - Antarctic saved message 2d10ae23-...
DEBUG artic     [b5483e6a] HTTP <-- POST / - responded 201 in 412 ms
````

Calls made straight to the gRPC port (`localhost:9090`) without the header get an id generated on
the antarctic side, so they are traceable too.

#### Third-party language detection

On save, artic asks [LibreTranslate](https://libretranslate.com) what language the message is
written in and stores the answer alongside it. It is off by default because the image is ~800MB
and downloads its models on first boot:

````
docker compose --profile translate up -d --build
curl -s -X POST localhost:8080/ -H 'Content-Type: application/json' -d '{"text":"Bonjour mon ami"}'
# {"id":"12e90c25-...","text":"Bonjour mon ami","language":"fr"}
````

Point it at the hosted service instead by setting `TRANSLATE_URL=https://libretranslate.com` and
`TRANSLATE_API_KEY`; nothing else changes.

The detector is treated as something that is allowed to be down. `LanguageDetector` never
throws - a timeout, a 5xx, an unparseable body or an unrecognised code all come back as null,
and the message saves without a language:

````
docker compose stop libretranslate
curl -s -X POST localhost:8080/ -H 'Content-Type: application/json' -d '{"text":"Bonjour encore"}'
# {"id":"9d3daa94-...","text":"Bonjour encore","language":null}   - still 201, in under 400ms
````

That is why the call has a short response timeout (it sits on the POST path, so a slow third
party must not stall callers), one retry limited to the transient cases, and a nullable
`language` column added by `V2__add_language.sql`.

#### Health and metrics

````
curl localhost:8080/actuator/health/liveness    # what the k8s livenessProbe hits
curl localhost:8080/actuator/health/readiness   # what the readinessProbe hits
curl localhost:8080/actuator/health             # per-component, including the antarctic hop
curl localhost:8080/actuator/prometheus         # micrometer metrics
````

Antarctic reachability is reported as its own component, using the standard gRPC health service:

````
"antarctic": { "status": "DOWN", "details": { "target": "antarctic:9090", "status": "DEADLINE_EXCEEDED" } }
````

It is deliberately kept out of the readiness group. If antarctic goes down, artic is still
working - it answers 503 per request - and failing its readiness would pull it out of the load
balancer as well, turning one outage into two.

### Option 2: using kubernetes and Minikube

Make sure you have Minikube installed.    

````
minikube start #(using virtualbox)    
minikube addons enable metrics-server    
eval $(minikube docker-env)    
docker build -t tern .    
cd deployment    
kubectl apply -f artic.yaml    
kubectl apply -f antarctic.yaml    
kubectl apply -f postgres.yaml
minikube dashboard
````

Both deployments declare liveness and readiness probes against the actuator endpoints above.

```minikube service artic```    

````
curl -d '{"text":"some-text"}' -H "Content-Type: application/json" -X POST {artic_host}    
curl {artic_host}
````

#### Istio
Make sure you have istio 1.18 installed.    

````
# to download istio:    
curl -L https://istio.io/downloadIstio | sh -
# from inside istio folder
export PATH=$PWD/bin:$PATH
istioctl install --set profile=demo -y

kubectl label namespace default istio-injection=enabled    

kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/prometheus.yaml    
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/kiali.yaml    
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/grafana.yaml    

istioctl dashboard kiali
````


#### Locust
In order to have a loadTest and see traffic animation on Kiali

<img width="766" alt="Screenshot 2023-07-02 at 16 01 47" src="https://github.com/Jouda-Hidri/Tern/assets/30729085/f7c67457-2a28-4841-9a17-edfa6f826a08">

To setup Locust, clone this project https://github.com/Jouda-Hidri/tern-lt

#### Tapi
After a message is saved, Tapi is called    
<img width="768" alt="Screenshot 2023-07-03 at 13 12 50" src="https://github.com/Jouda-Hidri/Tern/assets/30729085/17763716-9c9e-4247-9e4b-70ad0819b54b">    

To setup Tapi, clone this project: https://github.com/Jouda-Hidri/tapi

`TapiDownloader` treats it the same way as the language detector - as something allowed to be
down. It streams the CSV line by line so the file is never held in memory whole, runs off the
request thread so a save never waits on it, retries once on a transient failure, and logs and
drops anything else. Its read timeout is an *idle* timeout rather than a cap on the whole
transfer, so a large but progressing download is fine and only a stalled one is cut off.

## Tests

````
mvn verify
````

Unit tests cover the domain invariants, the repository mapping and the gRPC adapter's error
translation. `ArticServiceTest` runs a real gRPC server over the in-process transport, so the
stubs and status codes are genuinely exercised with only the remote implementation faked.
`MessageApiIntegrationTest` covers the whole path - HTTP into artic, gRPC to antarctic, a
Flyway-migrated Postgres in a Testcontainer, and back - which needs Docker running.

## CI/CD

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `CI` (`maven.yml`) | push / PR to main | `mvn verify` on Temurin 17, uploads the surefire reports, then builds the container image to prove a clean checkout is buildable |
| `CD` (`cd.yml`) | a green `CI` run on main | Publishes to GHCR tagged `latest` and `sha-<commit>`, and writes the `kubectl set image` rollout commands to the run summary |
| `DORA Lead Time` (`dora.yml`) | PR merged | Measures first-commit-to-merge lead time |

CD is triggered by `workflow_run`, so it publishes only what CI already proved green rather than
rebuilding and re-testing on a second trigger. Images are tagged with the commit sha so a
deployment can name exactly what it runs; the rollout itself is manual, since there is no
long-lived cluster to deploy to.

## Seeing it work

A guided tour of everything above, start to finish.

### 1. Bring the stack up

````
docker compose up --build -d
docker compose ps            # all three services report healthy
````

### 2. Call the API

````
curl -s localhost:8080/ | python3 -m json.tool
curl -i -X POST localhost:8080/ -H 'Content-Type: application/json' -d '{"text":"hi"}'
curl -i -X POST localhost:8080/ -H 'Content-Type: application/json' -d '{"text":"  "}'
````

A valid post answers `201` with the persisted message and its id, and an `X-Request-Id` header.
Blank text answers `400` with an error body carrying the same id.

### 3. Follow one request across both services

Take the `X-Request-Id` from any response above:

````
docker compose logs -f                 # live, interleaved across services
docker compose logs | grep <that-id>   # one request, artic and antarctic
````

### 4. Watch health degrade without cascading

````
curl -s localhost:8080/actuator/health | python3 -m json.tool
docker compose stop antarctic
curl -s localhost:8080/actuator/health | python3 -m json.tool   # antarctic DOWN, overall 503
curl -s localhost:8080/actuator/health/readiness                # still UP - artic stays in the LB
curl -i -X POST localhost:8080/ -H 'Content-Type: application/json' -d '{"text":"x"}'  # 503, not 500
docker compose start antarctic
````

### 5. Talk to gRPC directly

Server reflection is enabled, so no `.proto` file is needed:

````
docker run --rm fullstorydev/grpcurl -plaintext host.docker.internal:9090 list
docker run --rm fullstorydev/grpcurl -plaintext -d '{}' host.docker.internal:9090 tern.grpc.TernService/GetMessage
````

### 6. Run the tests

````
mvn verify
````

Watch for the Testcontainers Postgres starting up during `MessageApiIntegrationTest`.

### 7. Watch the pipeline

CI runs on every pull request against `main`; CD only fires once CI is green on `main`, and
publishes to `ghcr.io/<owner>/<repo>`. Both are visible under the repository's Actions tab.


