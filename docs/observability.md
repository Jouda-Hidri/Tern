# Istio, metrics and dashboards

Everything here is optional and applies to the Kubernetes setup described in the
[README](../README.md#running-it-on-kubernetes-with-minikube).

## Istio

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

## Metrics

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

## Dashboards

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

## Locust

In order to have a loadTest and see traffic animation on Kiali

<img width="766" alt="Screenshot 2023-07-02 at 16 01 47" src="https://github.com/Jouda-Hidri/Tern/assets/30729085/f7c67457-2a28-4841-9a17-edfa6f826a08">

To setup Locust, clone this project https://github.com/Jouda-Hidri/tern-lt
