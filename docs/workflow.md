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

## Three ways to reach Claude

`WORKFLOW_INVESTIGATOR` picks which one. They all take the same `AlertContext` and return the same
`Investigation`, so everything either side of them is identical.

| Value | Where it runs | Credential | Use it for |
| --- | --- | --- | --- |
| `api` (default) | anywhere, including a container | `ANTHROPIC_API_KEY` | anything real |
| `cli` | only on a machine with Claude Code logged in | none - the session already there | a private project, or trying it before paying for a key |
| `dry-run` | anywhere | none | proving the plumbing works without calling a model |

The seam is `Investigator` in `Investigator.kt` - two methods' worth of interface. `ApiInvestigator`
drives the tool loop itself against the Messages API. `CliInvestigator` hands the whole job to
`claude --print`, which already speaks MCP, and reads the report out of its JSON. `DryRunInvestigator`
asks the MCP servers what tools they have and reports that. Adding a fourth means one class and one
`@Bean`.

## Quick start: no key, no model

The fastest way to see the whole chain work:

````
WORKFLOW_ENABLED=true WORKFLOW_INVESTIGATOR=dry-run docker compose --profile workflow up -d --build
````

Prometheus fires, Alertmanager posts, the webhook is verified and parsed, the run is queued, and
the MCP servers are asked what tools they have. The report lists what was found instead of
investigating it. It is the fastest way to tell a broken deployment from a broken prompt.

## Quick start: your own Claude session, no key

`claude` authenticates as you, on your machine, so this one cannot run in the container. The
script stops the containerised artic and runs it locally in its place, against the rest of the
stack over published ports:

````
./run-local.sh
````

**This is not how you would do it in production.** A CLI session belongs to a person: it cannot be
rotated, scoped to a service, or handed to a deployment, and every investigation is billed to
whoever is logged in. It is here because this is a private project and it removes the only thing
standing between you and a working demo. For anything real, use `api`.

One consequence worth knowing: the alert text ends up in a prompt on your machine, and Claude Code
has tools that can act on it. `CliInvestigator` passes `--disallowed-tools Bash Write Edit
NotebookEdit WebFetch WebSearch` and `--strict-mcp-config`, so the session can reach the MCP servers
it was given and nothing else.

## Quick start: an API key

````
echo 'ANTHROPIC_API_KEY=sk-ant-...' >> .env
WORKFLOW_ENABLED=true docker compose --profile workflow up -d --build
````

Compose reads `.env` on its own, and it is gitignored. Add `PROMETHEUS_PORT` / `GRAFANA_PORT` /
`ALERTMANAGER_PORT` there too if another stack already owns 9091, 3000 or 9093 - they are host
ports only, and the services find each other by name regardless.

## Triggering an alert

Two ways, and they enter the service at exactly the same place.

**Stop the thing artic depends on.**

````
docker compose stop antarctic
````

Antarctic is the back end that owns the database, so stopping it is the most realistic breakage
available: artic immediately starts answering 503 and 504, and Prometheus stops being able to
scrape antarctic at all. It is the second of those that fires the alert. The chain, end to end,
takes about 75 seconds:

| | |
| --- | --- |
| t+0s | the container stops |
| t+15s | Prometheus misses a scrape, `up{job="tern",role="antarctic"}` goes to 0 |
| t+15s | `TernTargetDown` goes `pending` - the expression is true but `for: 1m` has not elapsed |
| t+75s | still true a minute later, so the rule goes `firing` and Prometheus notifies Alertmanager |
| t+85s | Alertmanager finishes its 10s `group_wait` and POSTs to `/workflow/alerts` |

Watch each stage:

````
curl -s localhost:9091/api/v1/rules     # inactive -> pending -> firing
curl -s localhost:9093/api/v2/alerts    # what Alertmanager is holding
docker compose logs -f artic | grep Workflow
````

Put antarctic back when you are done - `repeat_interval` is an hour, so an alert left firing is a
standing order for one investigation an hour:

````
docker compose start antarctic
````

**Or be the webhook yourself.** No waiting, no Prometheus, same code path from `AlertParser`
onwards:

````
curl -X POST localhost:8080/workflow/alerts -H 'Content-Type: application/json' \
  -d '{"title":"antarctic is unreachable","severity":"critical","labels":{"role":"antarctic"}}'
````

Then read the result:

````
curl -s localhost:8080/workflow/runs                # find the id
curl -s localhost:8080/workflow/runs/<id>/report    # the markdown
curl -s localhost:8080/workflow/runs/<id>           # tool calls, turns, tokens
````

**`TernArticErrors` is the rule you would expect to use, and it will not fire from a few curls.**
`rate()` does not count the jump from a series that does not exist yet to its first sample, so a
one-shot burst of errors against a freshly started container evaluates to zero. It needs errors
spread across several scrapes - `benchmark/load.sh`, or a loop running a couple of minutes.

**When artic runs locally**, Alertmanager is in a container and `artic` no longer resolves to
anything it can reach. Either POST the webhook yourself, as above, or point Alertmanager at the
host and restart it:

````
sed -i '' 's|http://artic:8080|http://host.docker.internal:8080|' deployment/workflow/alertmanager.yml
docker compose restart alertmanager
````

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

The module is off unless `WORKFLOW_ENABLED=true`. With it on it refuses to start if no MCP server
is configured, or if `api` is selected with no credential in the environment - failing at boot
rather than answering `202` to alerts it can never investigate.

| Variable | Default | Purpose |
| --- | --- | --- |
| `WORKFLOW_ENABLED` | `false` | Turns the module on. Only artic needs it |
| `WORKFLOW_INVESTIGATOR` | `api` | `api`, `cli` or `dry-run` - see above |
| `WORKFLOW_CLI_COMMAND` | `claude` | Which binary `cli` shells out to |
| `ANTHROPIC_API_KEY` | - | Read by the SDK from the environment. Required for `api`, unused by the other two |
| `PROMETHEUS_MCP_URL` / `GRAFANA_MCP_URL` / `KUBERNETES_MCP_URL` | - | MCP endpoints. Blank means "not configured"; at least one must be set |
| `WORKFLOW_WEBHOOK_SECRET` | - | HMAC-SHA256 secret. **Blank disables verification** - only acceptable when the endpoint is unreachable from outside the cluster, which is the case for Alertmanager, since it cannot sign |
| `WORKFLOW_SIGNATURE_HEADER` | `X-Tern-Signature` | `X-Incident-Signature` for incident.io |
| `WORKFLOW_MODEL` | `claude-opus-5` | |
| `WORKFLOW_EFFORT` | `high` | `api` only. `low`, `medium`, `high`, `xhigh`, `max`. Lower is cheaper and faster; `high` is a reasonable floor for something that has to read telemetry and be right |
| `WORKFLOW_MAX_TOKENS` | `16000` | `api` only. Per turn, covering thinking and text - the model thinks by default |
| `WORKFLOW_MAX_TURNS` | `24` | `api` only. Hard stop on the tool loop; `cli` bounds itself |
| `WORKFLOW_RUN_TIMEOUT` | `10m` | Wall-clock stop. Checked between turns under `api`, and kills the subprocess under `cli` |
| `WORKFLOW_CONCURRENCY` | `2` | Investigations in flight. Each one costs tokens |
| `WORKFLOW_QUEUE_DEPTH` | `32` | Queued investigations before new alerts are rejected |
| `WORKFLOW_HISTORY_SIZE` | `50` | Runs kept in memory |
| `WORKFLOW_SINK_URL` / `WORKFLOW_SINK_TOKEN` | - | Where the finished report is POSTed, with a bearer token |

## Things worth knowing before turning this on

**An alert is untrusted input.** Its annotations are free text written by whoever wrote the rule,
and on a hosted alert source by whoever can make one fire. That text goes into the prompt. The
defence is not prompt wording, it is that every tool is read-only at the credential: a read-only
Prometheus API, a Grafana service account scoped to Viewer, a Kubernetes ClusterRole with three
verbs. Keep it that way. Under `cli` this matters more, not less - the prompt is being handled by
a session on your own machine, which is why the tools that could write to it are refused
explicitly.

**It costs money per alert**, under `cli` as much as under `api` - the bill just moves to whoever
is logged in. A flapping rule is a bill. `WORKFLOW_CONCURRENCY` and
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
