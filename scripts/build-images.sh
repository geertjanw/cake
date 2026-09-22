#!/usr/bin/env bash
# Builds a local Docker image for each service, tagged for the Kubernetes manifests
# in k8s/. After this, load them into your cluster (see the README):
#   kind:  kind load docker-image cake-and-candles/<svc>:latest
#   k3s:   docker save cake-and-candles/<svc>:latest | sudo k3s ctr images import -
set -euo pipefail
cd "$(dirname "$0")/.."

for svc in party-service cake-service invitation-service; do
  echo "Building cake-and-candles/$svc:latest ..."
  docker build --build-arg MODULE="$svc" -t "cake-and-candles/$svc:latest" .
done

echo "Done. Images:"
docker images "cake-and-candles/*"
