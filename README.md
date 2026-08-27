# Tern

We are migrating the Tern service. Therefore, the same image is deployed twice: `Arctic` receives the production
traffic and delegates persistence to `Antarctic` (legacy) over gRPC, to avoid cascading HTTP calls.

````
    you ──HTTP──▶ artic ──gRPC──▶ antarctic ──▶ postgres
                    │
                    └──▶ libretranslate   detects the message language, after the response (optional)
````

| Service | Port | What it is |
| --- | --- | --- |
| `artic` | 8080 | The new version. REST API, Kotlin + Spring Boot |
| `antarctic` | 9090 | The legacy version. gRPC service - the same image, in its other role |
| `dbpostgresql` | 5432 | Postgres, schema managed by Flyway |
| `libretranslate` | 5050 | Language detection. Behind a profile, see below |

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

{"text":"Bonjour mon ami, comment vas-tu"}
````

Language detection does not happen on this path. The message is stored first and the response
sent; detection runs afterwards and stores the result with a second call to antarctic. So the
language appears in `/stats` a moment later, not in this response. Short inputs are hard to
detect: a whole sentence gives far better results than `"Bonjour!"`.

### `GET /` - list every message

````
curl -s localhost:8080/ | python3 -m json.tool
````

### `GET /stats` - counts by language

The only place the detected language is exposed. It is eventually consistent: a message posted
a moment ago may still be counted under `unknown`.

````
curl -s localhost:8080/stats
````

````
{"total":3,"byLanguage":{"en":1,"es":1,"fr":1}}
````

`unknown` counts messages whose language is not known yet - either detection has not finished,
or the detector was unavailable. It always sorts last.

### Logging

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

### Errors

Every error carries the request id, so a report can be tied straight back to the logs.

| Request | Answer |
| --- | --- |
| `{"text":"   "}` | `400` `text must not be blank` |
| `{"text":"<over 1000 chars>"}` | `400` `text must be at most 1000 characters` |
| `{"nope":1}` or invalid JSON | `400` `Request body is malformed or missing required fields` |
| `GET /` while antarctic is down | `503` `Antarctic is unavailable`, or `504` `Antarctic is deadline exceeded` |
| `POST /` while antarctic is down | `202` - see above |

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

## When things break

The detector is allowed to be down - it cannot stop a message being stored. Antarctic is
different: without it there is nothing to store into.

| Stop this | What happens |
| --- | --- |
| `docker compose stop libretranslate` | `201` as usual; the message stays under `unknown` in `/stats` |
| `docker compose stop antarctic` | `POST` answers `202`, `GET` answers `503` or `504` |

Readiness deliberately stays `UP` through all of it. Artic still works, and failing readiness
would pull it out of the load balancer too - turning one outage into two.

`GET` gives 503 if gRPC already knew the connection was dead, 504 if the call had to wait out
`ANTARCTIC_DEADLINE` first. After antarctic returns, the next request or two may still fail
while gRPC backs off; it clears in seconds.

## Health and metrics

````
curl localhost:8080/actuator/health/liveness    # what the k8s livenessProbe hits
curl localhost:8080/actuator/health/readiness   # what the readinessProbe hits
curl localhost:8080/actuator/health             # db, disk, liveness and readiness state
curl localhost:8080/actuator/prometheus         # micrometer metrics
````

Nothing scrapes this under docker-compose. Under Kubernetes the pods carry
`prometheus.io/scrape` annotations, so Istio's Prometheus collects these alongside the mesh
metrics - see below.

## Configuration

Everything is defaulted for Kubernetes and overridden by compose, so neither needs editing.

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_HOST` / `_DB` / `_USER` / `_PASSWORD` | - / `postgres` | Database connection |
| `ANTARCTIC_TARGET` | `antarctic.default.svc.cluster.local:30000` | Where artic finds antarctic |
| `ANTARCTIC_DEADLINE` | `2s` | Bounds every gRPC call, so an unreachable antarctic fails fast |
| `TRANSLATE_URL` | `http://libretranslate:5000` | Detector; point at `https://libretranslate.com` with `TRANSLATE_API_KEY` to use the hosted one |
| `TRANSLATE_TIMEOUT` | `2s` | How long the background detection waits before giving up |
| `LOG_LEVEL` | `INFO` | `DEBUG` for the full trace |
| `SERVICE_NAME` | `tern` | Which role this container is playing, shown in every log line |

## Tests

````
mvn verify
````

Needs Docker running, and **JDK 11-17** - the Kotlin 1.7.10 compiler cannot parse the version
string of newer JDKs, which is why CI pins Temurin 17. If `mvn -v` reports something newer:

````
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn verify
````

Unit tests cover the gRPC adapter's error translation and the HTTP status mapping.
`ArticServiceTest` runs a real gRPC server over the in-process transport, and
`LanguageDetectorTest` runs the detector against a MockWebServer to check what it does on
timeouts, 5xx and malformed bodies. `MessageApiIntegrationTest` covers the whole path - HTTP
into artic, gRPC to antarctic, a Flyway-migrated Postgres in a Testcontainer, and back.

## Running it locally using Kubernetes and Minikube

The same thing on a local cluster, plus Istio and autoscaling

Make sure you have Minikube installed.    

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

#### Istio

````
brew install istioctl        # or: curl -L https://istio.io/downloadIstio | sh -
istioctl install --set profile=demo -y

kubectl label namespace default istio-injection=enabled
kubectl rollout restart deployment/artic deployment/antarctic deployment/postgresql

kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/prometheus.yaml
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/kiali.yaml
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.18/samples/addons/grafana.yaml

istioctl dashboard kiali
````

Pods become `2/2` once the sidecar is injected. Istio identifies the hop between the two
services as gRPC and records it:

````
istio_requests_total{source_app="artic", destination_app="antarctic", request_protocol="grpc", response_code="200"}
````

The request id lines up for free: `X-Request-Id` is the header Envoy uses for its own
correlation, so one value ties the application logs to the sidecar access logs.

##### Metrics

Traffic has to enter through the ingress gateway to be measured - `kubectl port-forward` goes
straight to the pod and bypasses the mesh, which leaves only the gRPC hop visible:

````
kubectl apply -f deployment/istio-gateway.yaml
kubectl port-forward -n istio-system svc/istio-ingressgateway 8080:80

istioctl dashboard prometheus     # localhost:9090
istioctl dashboard kiali          # localhost:20001
istioctl dashboard grafana        # localhost:3000
````

Three queries for Prometheus:

````
# request count by status
sum by (destination_app,request_protocol,response_code) (istio_requests_total{reporter="destination"})

# requests per second
sum by (destination_app,request_protocol) (rate(istio_requests_total{reporter="destination"}[1m]))

# p95 latency
histogram_quantile(0.95, sum by (le,destination_app) (rate(istio_request_duration_milliseconds_bucket{reporter="destination"}[1m])))
````

Which gives both hops without the application measuring anything itself:

````
antarctic   grpc  200  2272        antarctic  22.16 req/s   p95   5 ms
artic       http  200   616        artic      21.62 req/s   p95  10 ms
artic       http  201   615
artic       http  400     1
artic       http  404     1
````

The pods are also annotated for scraping, so the same Prometheus holds the application's own
metrics. Istio merges them with the sidecar's on port 15020, so no extra scrape target is
needed:

````
# the same three, measured by the application rather than by Envoy
sum by (app,status,method) (http_server_requests_seconds_count{uri!~"/actuator.*"})
sum by (app,method) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m]))
histogram_quantile(0.95, sum by (le,app,method) (rate(http_server_requests_seconds_bucket{uri="/"}[1m])))

# and what only the application can report
jvm_memory_used_bytes{area="heap"}   hikaricp_connections_active   jvm_threads_live_threads
````

The two disagree slightly, and that is the point: Envoy measures at the sidecar, Micrometer
inside the JVM, so the gap between them is proxy and network time.

````
p95  artic  GET  14 ms (app)   vs   20 ms (Envoy)
````

##### Dashboards

Istio's own dashboards only query `istio_*`, so they show the mesh but not the application.
`deployment/grafana/tern-dashboard.json` covers the rest - RPS, status codes, latency
percentiles, the app-vs-Envoy gap, heap, Hikari and threads:

````
istioctl dashboard grafana
curl -sX POST localhost:3000/api/dashboards/db -H 'Content-Type: application/json' \
  -d "{\"dashboard\": $(cat deployment/grafana/tern-dashboard.json), \"overwrite\": true}"
````

Then **localhost:3000/d/tern-app**. For the mesh side use Istio's *Service* dashboard at
`localhost:3000/d/LJ_uJAvmk?var-service=artic.default.svc.cluster.local`.


#### Locust
In order to have a loadTest and see traffic animation on Kiali

<img width="766" alt="Screenshot 2023-07-02 at 16 01 47" src="https://github.com/Jouda-Hidri/Tern/assets/30729085/f7c67457-2a28-4841-9a17-edfa6f826a08">

To setup Locust, clone this project https://github.com/Jouda-Hidri/tern-lt

## CI/CD

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `CI` (`maven.yml`) | push / PR to main | `mvn verify` on Temurin 17, uploads the surefire reports, then builds the container image to prove a clean checkout is buildable |
| `CD` (`cd.yml`) | a green `CI` run on main | Publishes to Amazon ECR tagged `latest` and `sha-<commit>`, and writes the `kubectl set image` rollout commands to the run summary |
| `DORA Lead Time` (`dora.yml`) | PR merged | Measures first-commit-to-merge lead time |

CD is triggered by `workflow_run`, so it publishes only what CI already proved green rather than
rebuilding and re-testing on a second trigger. Images are tagged with the commit sha so a
deployment can name exactly what it runs; the rollout itself is manual, since there is no
long-lived cluster to deploy to.

CD is the only part that needs an AWS account, and only for publishing. Without one, `CI` still
runs on every push and pull request, and running it locally works unchanged -
both options build the image from source.

`CD` is what breaks. With no `AWS_ROLE_ARN` variable set, `role-to-assume` is empty and the run
stops after a few seconds with:

````
Error: Credentials could not be loaded, please check your action inputs:
Could not load credentials from any providers
````

Set the variable as described in [the next section](#where-cd-pushes-to-and-how-it-authenticates),
or delete `.github/workflows/cd.yml` if the red run is just noise.

### Where CD pushes to, and how it authenticates

`deployment/aws/ecr-oidc.yaml` creates the ECR repository and an IAM role GitHub Actions assumes
through OIDC, so **no AWS access key exists anywhere**. The role's trust policy accepts only a
token whose audience is `sts.amazonaws.com` and whose subject names your repository on
`refs/heads/main`.

This is deployed and in use: stack `tern-ci` in `eu-central-1`, and CD pushes to it on every green
build of main. To set it up in another account:

````
aws configure sso                  # first time
aws sso login --profile <name>     # afterwards, to refresh

aws cloudformation deploy \
  --region eu-central-1 \
  --stack-name tern-ci \
  --template-file deployment/aws/ecr-oidc.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides GitHubRepository=<owner>/<repo>

aws cloudformation describe-stacks --region eu-central-1 --stack-name tern-ci \
  --query 'Stacks[0].Outputs' --output table
````

If the account already has a GitHub OIDC provider, add `CreateOidcProvider=false` - an account
may only hold one per URL.

Then hand the role ARN from those outputs to GitHub. It is a repository *variable*, not a
secret - a role ARN is not sensitive, and it is worthless without a token from your repository:

````
gh variable set AWS_ROLE_ARN --body 'arn:aws:iam::<account-id>:role/tern-ci-github-actions'
````

A **variable**, not a secret - `cd.yml` reads `vars.AWS_ROLE_ARN`, so a secret of the same name
resolves to empty and reproduces the error above.

Pull what CD published:

````
aws ecr get-login-password --region eu-central-1 \
  | docker login --username AWS --password-stdin <account-id>.dkr.ecr.eu-central-1.amazonaws.com
docker pull --platform linux/amd64 <account-id>.dkr.ecr.eu-central-1.amazonaws.com/tern:latest
````

GitHub's runners are x86_64, so CD publishes `linux/amd64` only - hence `--platform` on arm64
machines. Building locally has no such constraint.

A lifecycle policy keeps the last two builds and expires untagged manifests after a day, which
holds the repository inside the 500MB free tier. Scanning on push is enabled.
