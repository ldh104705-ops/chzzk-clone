#!/usr/bin/env bash
# Creates the local kind cluster and deploys the whole stack (assignment 5).
#
# Needs on PATH: docker (running), kind, kubectl, openssl (or /dev/urandom). On Windows run it from Git Bash and make
# sure kind.exe is on PATH, e.g. (winget installs it here):
#   export PATH="$PATH:/c/Users/<you>/AppData/Local/Microsoft/WinGet/Packages/Kubernetes.kind_Microsoft.Winget.Source_8wekyb3d8bbwe"
#
# Steps: create cluster -> build both images -> load them into kind -> install metrics-server -> create the Secret with
# freshly generated random values -> apply the manifests -> wait for everything to be ready.
# Open http://localhost:8090 afterwards.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER=chzzk
NS=chzzk
METRICS_SERVER_URL="${METRICS_SERVER_URL:-https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.1/components.yaml}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' not found on PATH" >&2; exit 1; }; }
need docker; need kind; need kubectl

random() { # random <bytes> -> hex string, without needing openssl
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex "$1"; else head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; fi
}

echo "==> kind cluster '$CLUSTER'"
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "    already exists, reusing it"
else
  kind create cluster --config "$ROOT/deploy/kind-config.yaml" --wait 120s
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

echo "==> building images"
docker build -t chzzk-backend:local "$ROOT/backend"
docker build -t chzzk-frontend:local "$ROOT/frontend"

echo "==> loading images into kind"
kind load docker-image chzzk-backend:local chzzk-frontend:local --name "$CLUSTER"

echo "==> metrics-server (the HPA reads pod CPU from it; --kubelet-insecure-tls because kind's kubelet cert is self-signed)"
kubectl apply -f "$METRICS_SERVER_URL"
if ! kubectl -n kube-system get deploy metrics-server -o jsonpath='{.spec.template.spec.containers[0].args}' | grep -q kubelet-insecure-tls; then
  kubectl -n kube-system patch deployment metrics-server --type=json \
    -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi

echo "==> namespace and Secret (random values generated now, never written to disk or git)"
kubectl apply -f "$ROOT/deploy/k8s/namespace.yaml"
if kubectl -n "$NS" get secret chzzk-secrets >/dev/null 2>&1; then
  echo "    chzzk-secrets already exists, keeping it (down.sh deletes the cluster and with it the Secret)"
else
  SECRET_ARGS=(
    --from-literal=APP_JWT_SECRET="$(random 48)"
    --from-literal=POSTGRES_PASSWORD="$(random 16)"
    --from-literal=MINIO_ROOT_USER="chzzk$(random 4)"
    --from-literal=MINIO_ROOT_PASSWORD="$(random 16)"
  )
  # Optional Toss TEST keys, only if exported in your shell (donations stay disabled otherwise).
  [ -n "${TOSS_CLIENT_KEY:-}" ] && SECRET_ARGS+=(--from-literal=TOSS_CLIENT_KEY="$TOSS_CLIENT_KEY")
  [ -n "${TOSS_SECRET_KEY:-}" ] && SECRET_ARGS+=(--from-literal=TOSS_SECRET_KEY="$TOSS_SECRET_KEY")
  kubectl -n "$NS" create secret generic chzzk-secrets "${SECRET_ARGS[@]}"
fi

echo "==> applying manifests"
kubectl apply -k "$ROOT/deploy/k8s"

echo "==> waiting for rollouts"
kubectl -n "$NS" rollout status statefulset/postgres --timeout=180s
kubectl -n "$NS" rollout status deployment/redis --timeout=120s
kubectl -n "$NS" rollout status deployment/minio --timeout=180s
kubectl -n "$NS" rollout status deployment/backend --timeout=300s
kubectl -n "$NS" rollout status deployment/frontend --timeout=120s
kubectl -n kube-system rollout status deployment/metrics-server --timeout=180s

echo
kubectl -n "$NS" get deploy,hpa,pods
echo
echo "Ready: http://localhost:8090   (watch scaling with deploy/scripts/watch.sh, load with load/k6/scale-test.js)"
