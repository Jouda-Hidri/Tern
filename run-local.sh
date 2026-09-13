#!/usr/bin/env bash
# Runs artic on this machine instead of in a container, so the investigation can use the Claude
# Code session you are already logged into rather than an API key. Everything else - antarctic,
# Postgres, Prometheus, Alertmanager, the MCP servers - stays in compose and is reached over
# published ports.
#
# In production this would be an API key and a container. A CLI session belongs to a person, not
# to a service: it cannot be rotated, scoped, or handed to a deployment.
set -euo pipefail
cd "$(dirname "$0")"

command -v "${WORKFLOW_CLI_COMMAND:-claude}" >/dev/null \
  || { echo "No '${WORKFLOW_CLI_COMMAND:-claude}' on PATH. Install Claude Code, or set WORKFLOW_INVESTIGATOR=api"; exit 1; }

docker compose --profile workflow up -d
# Frees 8080, which the local process is about to take.
docker compose stop artic

export POSTGRES_HOST=localhost POSTGRES_DB=polldb POSTGRES_USER=postgres POSTGRES_PASSWORD=password
export ANTARCTIC_TARGET=localhost:9090
export SERVICE_NAME=artic
# The image serves gRPC in either role, but only antarctic needs it - and antarctic already has
# 9090 on this machine. -1 turns the server off; artic is only a gRPC client.
export GRPC_SERVER_PORT=-1
export WORKFLOW_ENABLED=true
export WORKFLOW_INVESTIGATOR="${WORKFLOW_INVESTIGATOR:-cli}"
# Container names do not resolve out here, so the MCP servers are reached on their published ports.
export PROMETHEUS_MCP_URL="${PROMETHEUS_MCP_URL:-http://localhost:8000/mcp}"

echo "artic on http://localhost:8080, investigating with WORKFLOW_INVESTIGATOR=$WORKFLOW_INVESTIGATOR"
echo "To have Alertmanager reach it, see docs/workflow.md. To stop: Ctrl-C, then docker compose up -d artic"
exec mvn -q spring-boot:run
