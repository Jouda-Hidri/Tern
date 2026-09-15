#!/usr/bin/env bash
# Runs artic on this machine so the investigation can use the Claude Code session you are logged
# into. Everything else stays in compose and is reached over published ports.
#
# Not a production shape: a CLI session belongs to a person, not a service.
set -euo pipefail
cd "$(dirname "$0")"

command -v "${WORKFLOW_CLI_COMMAND:-claude}" >/dev/null \
  || { echo "No '${WORKFLOW_CLI_COMMAND:-claude}' on PATH. Install Claude Code, or use WORKFLOW_INVESTIGATOR=dry-run in compose"; exit 1; }

# Kotlin 1.9 cannot parse a Java 26 version string, and that is what `mvn` picks up by default.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export JAVA_HOME
fi

docker compose --profile workflow up -d
# Frees 8080, which the local process is about to take.
docker compose stop artic

# A run killed rather than stopped leaves its JVM on 8080, and Spring's error never names it.
if holder="$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null)" && [ -n "$holder" ]; then
  echo "Port 8080 is still held by pid(s): $holder"
  echo "Probably a previous ./run-local.sh. Stop it with: kill $holder"
  exit 1
fi

export POSTGRES_HOST=localhost POSTGRES_DB=polldb POSTGRES_USER=postgres POSTGRES_PASSWORD=password
export ANTARCTIC_TARGET=localhost:9090
export SERVICE_NAME=artic
# Only antarctic needs a gRPC server, and it already has 9090 here. Artic is only a client.
export GRPC_SERVER_PORT=-1
export WORKFLOW_ENABLED=true
export WORKFLOW_INVESTIGATOR="${WORKFLOW_INVESTIGATOR:-cli}"
# Container names do not resolve out here, so the MCP servers are reached on their published ports.
export PROMETHEUS_MCP_URL="${PROMETHEUS_MCP_URL:-http://localhost:8000/mcp}"
export DOCKER_MCP_URL="${DOCKER_MCP_URL:-http://localhost:${DOCKER_MCP_PORT:-8002}/mcp}"

echo "artic on http://localhost:8080, investigating with WORKFLOW_INVESTIGATOR=$WORKFLOW_INVESTIGATOR"
echo "To have Alertmanager reach it, see docs/workflow.md. To stop: Ctrl-C, then docker compose up -d artic"
exec mvn -q spring-boot:run
