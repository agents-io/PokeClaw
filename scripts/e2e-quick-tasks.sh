#!/bin/bash
set -euo pipefail

MODE="${1:-cloud}"
TRIALS="${2:-1}"
RESULTS_DIR="${RESULTS_DIR:-}"
CLOUD_MODEL_NAME="${CLOUD_MODEL_NAME:-gpt-4.1}"

case "$MODE" in
  cloud)
    SUITE="cloud-full"
    ;;
  local)
    SUITE="local-full"
    ;;
  *)
    echo "Usage: $0 [cloud|local] [trials]" >&2
    exit 1
    ;;
esac

CMD=(
  python3
  scripts/qa_harness_v2.py
  --mode "$MODE"
  --suite "$SUITE"
  --trials "$TRIALS"
  --cloud-model "$CLOUD_MODEL_NAME"
)

if [[ -n "$RESULTS_DIR" ]]; then
  CMD+=(--results-dir "$RESULTS_DIR")
fi

exec "${CMD[@]}"
