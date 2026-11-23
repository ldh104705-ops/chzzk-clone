#!/usr/bin/env bash
# Deletes the local kind cluster (and with it every pod, volume and the generated Secret).
# The docker-compose Postgres on your machine is not touched.
set -euo pipefail

command -v kind >/dev/null 2>&1 || { echo "ERROR: 'kind' not found on PATH" >&2; exit 1; }
kind delete cluster --name chzzk
