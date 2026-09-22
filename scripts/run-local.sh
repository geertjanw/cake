#!/usr/bin/env bash
# Run all three services on your machine (no Docker) with the OTel Java agent
# attached, exporting to Dash0. Needs Java 21, Maven, and a .env file.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] && set -a && source .env && set +a
: "${DASH0_AUTH_TOKEN:?Set DASH0_AUTH_TOKEN in .env}"
DASH0_ENDPOINT="${DASH0_ENDPOINT:-https://ingress.eu-west-1.aws.dash0.com:4317}"

AGENT=opentelemetry-javaagent.jar
if [ ! -f "$AGENT" ]; then
  echo "Downloading OpenTelemetry Java agent..."
  curl -sSL -o "$AGENT" https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
fi

mvn -q -B -DskipTests package

export OTEL_EXPORTER_OTLP_ENDPOINT="$DASH0_ENDPOINT"
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer ${DASH0_AUTH_TOKEN},Dash0-Dataset=${DASH0_DATASET:-default}"
export OTEL_RESOURCE_ATTRIBUTES="deployment.environment.name=${DEPLOYMENT_ENV:-local},service.namespace=cake-and-candles,service.version=0.1.0"
export OTEL_LOGS_EXPORTER=otlp OTEL_METRICS_EXPORTER=otlp OTEL_TRACES_EXPORTER=otlp
export OTEL_METRIC_EXPORT_INTERVAL=15000
export OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_EMIT_EXPERIMENTAL_TELEMETRY=true

pids=()
for svc in cake-service invitation-service party-service; do
  OTEL_SERVICE_NAME=$svc java -javaagent:$AGENT -jar $svc/target/$svc-*.jar > $svc.log 2>&1 &
  pids+=($!)
  echo "Started $svc (pid $!) - logs in $svc.log"
done

trap 'echo; echo "Stopping..."; kill "${pids[@]}" 2>/dev/null' INT TERM
echo "All services up. party-service: http://localhost:8080  Ctrl-C to stop."
wait
