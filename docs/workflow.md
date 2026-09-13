# Alert investigation workflow

An alert fires, a webhook lands on artic, and the service investigates it with Claude through
read-only MCP servers onto Prometheus and Docker. What comes back is a markdown report.

````
Prometheus ──rule fires──▶ Alertmanager ──webhook──▶ artic  POST /workflow/alerts
                                                             │  202 + run id
                                                             ▼
                                               InvestigationRunner (2 at a time)
                                                             │
                                          ┌──────────────────┴──────────────────┐
                                          ▼                                     ▼
                                   prometheus-mcp                          docker-mcp
                                                                    (socket proxy, GET only)
                                          └──────────────────┬──────────────────┘
                                                             │  tools/list, tools/call
                                                             ▼
                                                  claude --print, your session
                                                             │
                                                  GET /workflow/runs/{id}/report
````

The [README](../README.md#investigating-an-alert) has the steps. This is what is behind them.

## The two investigators

`WORKFLOW_INVESTIGATOR` picks one. Both take an `AlertContext` and return an `Investigation`, so
everything either side of them is identical.

| | What it does |
| --- | --- |
| `dry-run` (default) | Asks each MCP server for its tools and reports what it found. No model. |
| `cli` | Shells out to `claude --print`, which already speaks MCP, and reads the report out of its JSON. |

`cli` uses the Claude Code session of whoever started the process, so it only runs on a machine,
never in the container - which is what `run-local.sh` is for. **A session belongs to a person: it
cannot be rotated, scoped to a service, or handed to a deployment.** In production this would be
an API key and a container. `Investigator` is a one-method interface, so that is one class.

## What a run does

1. `POST /workflow/alerts` answers `202` with a run id per firing alert. Resolved alerts are
   dropped. The endpoint has no authentication - keep it off the public internet.
2. The run is queued on `WORKFLOW_CONCURRENCY` threads with a bounded queue. A full queue answers
   `202` with the run already `REJECTED` rather than growing without limit.
3. Each MCP server is initialised and asked for `tools/list`. Tools are renamed `server__tool`. A
   server that fails is logged and skipped; if every server fails the run fails, because a report
   written with no telemetry is worse than no report.
4. `claude --print` runs the loop, bounded by `WORKFLOW_RUN_TIMEOUT`. It is given
   `--strict-mcp-config` and `--disallowed-tools Bash Write Edit NotebookEdit WebFetch WebSearch`,
   so it can reach the MCP servers it was handed and nothing else.
5. The final message is the report, in fixed sections: summary, impact, evidence, likely cause,
   next steps, confidence.

Runs are kept in memory, newest `WORKFLOW_HISTORY_SIZE` first. They do not survive a restart.

## Endpoints

| Request | Answer |
| --- | --- |
| `POST /workflow/alerts` | `202` with one run id per firing alert; `400` on malformed JSON |
| `GET /workflow/runs` | Every run held in memory, newest first |
| `GET /workflow/runs/{id}` | State, timings, tool calls, token counts, report |
| `GET /workflow/runs/{id}/report` | Just the markdown; `202` while the run is still going |

## Configuration

Off unless `WORKFLOW_ENABLED=true`. With it on, it refuses to start if no MCP server is
configured, rather than answering `202` to alerts it can never investigate.

| Variable | Default | Purpose |
| --- | --- | --- |
| `WORKFLOW_ENABLED` | `false` | Turns the module on. Only artic needs it |
| `WORKFLOW_INVESTIGATOR` | `dry-run` | `dry-run` or `cli` |
| `WORKFLOW_CLI_COMMAND` | `claude` | Which binary `cli` shells out to |
| `WORKFLOW_MODEL` | `claude-opus-5` | Passed to `claude --model` |
| `PROMETHEUS_MCP_URL` / `DOCKER_MCP_URL` | set by compose | Blank means "not configured"; at least one must be set |
| `WORKFLOW_RUN_TIMEOUT` | `10m` | Kills the subprocess past this |
| `WORKFLOW_CONCURRENCY` | `2` | Investigations in flight |
| `WORKFLOW_QUEUE_DEPTH` | `32` | Queued before new alerts are rejected |
| `WORKFLOW_HISTORY_SIZE` | `50` | Runs kept in memory |
| `PROMETHEUS_PORT` / `GRAFANA_PORT` / `ALERTMANAGER_PORT` / `DOCKER_MCP_PORT` | `9091` / `3000` / `9093` / `8002` | Host ports only. Override in `.env` if another stack owns them |

## Why the alert fires when it does

`docker compose stop antarctic` is the trigger because Prometheus then cannot scrape it. It is
the missed scrape that fires the rule, not the 503s artic starts returning:

| | |
| --- | --- |
| t+0s | the container stops |
| t+15s | `up{job="tern",role="antarctic"}` goes to 0, `TernTargetDown` goes `pending` |
| t+75s | still true after `for: 1m`, so the rule fires and notifies Alertmanager |
| t+85s | Alertmanager finishes its 10s `group_wait` and POSTs to `/workflow/alerts` |

Watch each stage:

````
curl -s localhost:9091/api/v1/rules     # inactive -> pending -> firing
curl -s localhost:9093/api/v2/alerts    # what Alertmanager is holding
docker compose logs -f artic | grep Workflow
````

`TernArticErrors` is the rule you would expect to use instead, and it will not fire from a few
curls: `rate()` does not count the jump from a series that does not exist yet to its first
sample, so a one-shot burst against a freshly started container evaluates to zero. It needs
errors spread over several scrapes.

Alertmanager posts to `host.docker.internal:8080` rather than `artic:8080`, so the same config
works whether artic is the container or a local process - compose publishes 8080 on the host
either way. The `extra_hosts` entry in docker-compose.yml is what makes that name resolve on
Linux; Docker Desktop provides it already.

## Things worth knowing before turning this on

**An alert is untrusted input.** Its annotations are free text, and they go into the prompt. The
defence is not prompt wording, it is that every tool is read-only at the credential. Under `cli`
that matters more, not less: the prompt is handled by a session on your own machine, which is why
the tools that could write to it are refused explicitly.

**The Docker socket is root on the host.** Anything holding it can start a privileged container
and own the machine, and mounting it `:ro` does not help - the socket is an API endpoint, and a
read-only bind mount does not stop write calls through it. The MCP server never sees it.
`docker-socket-proxy` holds it and allows GET on the container endpoints, answering 403 to
everything else, which was checked by asking it to create and to kill a container.

**Every run costs tokens**, `cli` included - the bill moves to whoever is logged in.
`WORKFLOW_CONCURRENCY` and `WORKFLOW_QUEUE_DEPTH` bound what is in flight; Alertmanager's
`repeat_interval` is an hour, so an alert left firing is a standing order for one run an hour.

**Image tags drift.** The MCP servers are third-party images on `latest`
(`ghcr.io/pab1it0/prometheus-mcp-server`, `codeberg.org/jhot/docker-mcp`). Their flags and
transports change between releases. If a server never appears in `Workflow - MCP servers:`, check
its own README before suspecting this code. Pin the digests before relying on any of it.

**The report is graded by nothing.** It says what it measured and how confident it is, and the
confidence section exists so a reader can tell a measurement from a guess. Treat "next steps" as
a suggestion to a human, not a runbook to execute. When it says it could not determine something
because no tool existed, that is a list of what to connect next.
