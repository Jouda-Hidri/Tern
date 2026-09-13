# Alert investigation workflow

An alert fires, a webhook lands on artic, and the service investigates it with Claude through
MCP servers that read Prometheus, Grafana and the Kubernetes API. What comes back is a markdown
report at `GET /workflow/runs/{id}/report`.

````
Prometheus ──rule fires──▶ Alertmanager ──webhook──▶ artic  POST /workflow/alerts
   (or incident.io, or anything that POSTs JSON)                 │  202 + run id
                                                                 ▼
                                                    InvestigationRunner (2 at a time)
                                                                 │
                                        ┌────────────────────────┼─────────────────────┐
                                        ▼                        ▼                     ▼
                                  prometheus-mcp            grafana-mcp          kubernetes-mcp
                                        └────────────────────────┴─────────────────────┘
                                                     tools/list, tools/call
                                                                 │
                                                    Claude (claude-opus-5) tool loop
                                                                 │
                                            GET /workflow/runs/{id}/report, and
                                            optionally POSTed on to a sink
````

## Can incident.io run in docker-compose or Kubernetes?

No. incident.io is a hosted product - there is no self-hosted distribution, no container image,
and no on-prem licence. Nothing you can `docker compose up` is incident.io. It joins this at two
points, both over the network to their API:

- **As a source.** An incident.io alert route can POST to `/workflow/alerts`. That needs the
  endpoint reachable from the internet: an Ingress in Kubernetes, or `cloudflared tunnel` /
  `ngrok http 8080` locally. Their webhooks are signed, so set `WORKFLOW_WEBHOOK_SECRET` and
  `WORKFLOW_SIGNATURE_HEADER=X-Incident-Signature`.
- **As a sink.** `WORKFLOW_SINK_URL` takes the finished report and POSTs it somewhere with a
  bearer token. Point it at the incident.io endpoint your account uses for incident updates,
  at a Slack webhook, or at anything else that accepts JSON. Check the path against their
  current API reference - it is versioned and this repo does not pin it.

What *can* run locally is the alerting half: Prometheus and Alertmanager, under the `workflow`
profile. That is what the quick start below uses, and the code path is identical - the webhook
handler normalises Alertmanager, incident.io and a generic `{title, severity, labels}` shape into
the same `AlertContext`.

## Quick start on docker-compose

````
export ANTHROPIC_API_KEY=sk-ant-...
WORKFLOW_ENABLED=true docker compose --profile workflow up -d --build
````

That starts Prometheus (scraping both roles), Alertmanager (webhooking artic), Grafana, and the
Prometheus MCP server. Then make something break:

````
docker compose stop antarctic          # artic starts answering 503/504
curl -s -o /dev/null localhost:8080/   # give the rule something to see
````

`TernArticErrors` fires after a minute, Alertmanager groups for ten seconds and POSTs. Watch it:

````
docker compose logs -f artic | grep Workflow
curl -s localhost:8080/workflow/runs | python3 -m json.tool
curl -s localhost:8080/workflow/runs/<id>/report
````

Or skip the alerting stack and POST an alert yourself:

````
curl -i -X POST localhost:8080/workflow/alerts -H 'Content-Type: application/json' -d '{
  "alerts": [{"status":"firing","labels":{"alertname":"TernArticErrors","severity":"critical"},
              "annotations":{"description":"artic is answering 503 on every path"},
              "startsAt":"2026-09-11T10:00:00Z"}]}'
````

Grafana needs a service account token before `grafana-mcp` is useful. Create one at
**localhost:3000 → Administration → Service accounts**, then restart with `GRAFANA_TOKEN=...` and
`GRAFANA_MCP_URL=http://grafana-mcp:8000/mcp`. Without it, leave `GRAFANA_MCP_URL` unset and the
investigation runs on Prometheus alone.

## On Kubernetes

````
kubectl create secret generic workflow-secrets \
  --from-literal=anthropic_api_key=$ANTHROPIC_API_KEY \
  --from-literal=webhook_secret=$(openssl rand -hex 32)

kubectl apply -f deployment/workflow/kubernetes-mcp.yaml -f deployment/workflow/alertmanager.yaml
kubectl set env deployment/artic WORKFLOW_ENABLED=true
````

`kubernetes-mcp.yaml` binds a ServiceAccount to a ClusterRole with `get`/`list`/`watch` and
nothing else, and runs the server with `--read-only --disable-destructive`. Both matter: an
alert annotation is attacker-influenceable text that ends up in the prompt, so the investigation
must not be *able* to mutate the cluster even if it is talked into trying.

`alertmanager.yaml` also carries the Prometheus MCP server, pointed at Istio's addon Prometheus
(see [observability](observability.md)). Point `PROMETHEUS_URL` at your own if you run one, and
load `deployment/workflow/rules.yml` into it so there is something to fire.

## What a run does

1. `POST /workflow/alerts` verifies the HMAC-SHA256 signature over the raw body, if a secret is
   set, and answers `202` with a run id per firing alert. Resolved alerts are dropped.
2. The run is queued on a pool of `WORKFLOW_CONCURRENCY` threads with a bounded queue. A full
   queue answers `202` with the run already in state `REJECTED` rather than growing unboundedly.
3. Each MCP server is initialised (`initialize`, `notifications/initialized`) and asked for
   `tools/list`. The tools are renamed `server__tool` and handed to Claude as ordinary tools. A
   server that fails is logged and skipped; if every configured server fails the run fails,
   because a report written with no telemetry is worse than no report.
4. Claude runs a tool loop, bounded by `WORKFLOW_MAX_TURNS` and `WORKFLOW_RUN_TIMEOUT`. Tool
   results are truncated at 20k characters. A failing tool comes back as an error result rather
   than killing the run, so the model can route around it and say so in the report.
5. The final message is the report, in fixed sections: summary, impact, evidence, likely cause,
   next steps, confidence. It is logged, stored, and POSTed to the sink if one is configured.

Runs are kept in memory, newest `WORKFLOW_HISTORY_SIZE` first. They do not survive a restart and
are not shared between replicas - deliberately, since a report is disposable once it has been
read or forwarded to the sink. Put it in Postgres if you want it to outlive the pod.

## Endpoints

| Request | Answer |
| --- | --- |
| `POST /workflow/alerts` | `202` with one run id per firing alert; `401` on a bad signature; `400` on malformed JSON |
| `GET /workflow/runs` | Every run held in memory, newest first |
| `GET /workflow/runs/{id}` | One run: state, timings, tool calls, token counts, report |
| `GET /workflow/runs/{id}/report` | Just the markdown; `202` while the run is still going |

## Configuration

The module is off unless `WORKFLOW_ENABLED=true`, and refuses to start if it is on with no MCP
server configured - failing at boot rather than answering `202` to alerts it can never
investigate.

| Variable | Default | Purpose |
| --- | --- | --- |
| `WORKFLOW_ENABLED` | `false` | Turns the module on. Only artic needs it |
| `ANTHROPIC_API_KEY` | - | Read by the SDK from the environment |
| `PROMETHEUS_MCP_URL` / `GRAFANA_MCP_URL` / `KUBERNETES_MCP_URL` | - | MCP endpoints. Blank means "not configured"; at least one must be set |
| `WORKFLOW_WEBHOOK_SECRET` | - | HMAC-SHA256 secret. **Blank disables verification** - only acceptable when the endpoint is unreachable from outside the cluster, which is the case for Alertmanager, since it cannot sign |
| `WORKFLOW_SIGNATURE_HEADER` | `X-Tern-Signature` | `X-Incident-Signature` for incident.io |
| `WORKFLOW_MODEL` | `claude-opus-5` | |
| `WORKFLOW_EFFORT` | `high` | `low`, `medium`, `high`, `xhigh`, `max`. Lower is cheaper and faster; `high` is a reasonable floor for something that has to read telemetry and be right |
| `WORKFLOW_MAX_TOKENS` | `16000` | Per turn, covering thinking and text - the model thinks by default |
| `WORKFLOW_MAX_TURNS` | `24` | Hard stop on the tool loop |
| `WORKFLOW_RUN_TIMEOUT` | `10m` | Wall-clock stop, checked between turns |
| `WORKFLOW_CONCURRENCY` | `2` | Investigations in flight. Each one costs tokens |
| `WORKFLOW_QUEUE_DEPTH` | `32` | Queued investigations before new alerts are rejected |
| `WORKFLOW_HISTORY_SIZE` | `50` | Runs kept in memory |
| `WORKFLOW_SINK_URL` / `WORKFLOW_SINK_TOKEN` | - | Where the finished report is POSTed, with a bearer token |

## Things worth knowing before turning this on

**An alert is untrusted input.** Its annotations are free text written by whoever wrote the rule,
and on a hosted alert source by whoever can make one fire. That text goes into the prompt. The
defence is not prompt wording, it is that every tool is read-only at the credential: a read-only
Prometheus API, a Grafana service account scoped to Viewer, a Kubernetes ClusterRole with three
verbs. Keep it that way.

**It costs money per alert.** A flapping rule is a bill. `WORKFLOW_CONCURRENCY` and
`WORKFLOW_QUEUE_DEPTH` bound how much can be in flight, and Alertmanager's `repeat_interval` and
`group_interval` bound how often the same alert arrives. Set `max_alerts` on the webhook receiver
so a storm does not arrive as one enormous payload.

**Image tags drift.** The MCP servers here are third-party images pinned to `latest`
(`ghcr.io/pab1it0/prometheus-mcp-server`, `mcp/grafana`, `ghcr.io/containers/kubernetes-mcp-server`).
Their flags and transport names change between releases. If a server never appears in
`Workflow - MCP servers:` or `tools/list` fails, check its own README for the current
streamable-HTTP flag before suspecting this code. Pin the digests before you rely on any of it.

**The report is a starting point.** It is graded by nothing. It says what it measured and how
confident it is, and the confidence section exists so a reader can tell a measurement from a
guess. Treat "next steps" as a suggestion to a human, not a runbook to execute.
