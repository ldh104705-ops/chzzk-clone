#!/usr/bin/env bash
# Prints the HPA and the backend Deployment/pods every few seconds, each block with a timestamp.
# Usage: deploy/scripts/watch.sh [interval-seconds]   (Ctrl+C to stop)
# Redirect to a file to keep a record:  deploy/scripts/watch.sh 10 | tee scale-watch.log
INTERVAL="${1:-10}"
while true; do
  echo "--- $(date '+%Y-%m-%d %H:%M:%S')"
  kubectl get hpa,deploy -n chzzk 2>&1 | grep -E 'NAME|backend|frontend|hpa'
  kubectl get pods -n chzzk -l app=backend --no-headers 2>&1 | awk '{printf "    %s %s %s\n", $1, $2, $3}'
  sleep "$INTERVAL"
done
