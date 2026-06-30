#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${FACEBOOK_RPA_TOOL_HOME:-$HOME/.recruitment-rpa/facebook-group-tool}"
PORT="${FACEBOOK_RPA_PORT:-18990}"

if [ ! -f "${TARGET_DIR}/package.json" ]; then
  echo "Missing RPA tool at ${TARGET_DIR}."
  echo "Run ./scripts/setup-facebook-group-rpa-wsl.sh first."
  exit 1
fi

if command -v ss >/dev/null 2>&1 && ss -ltn "sport = :${PORT}" | grep -q ":${PORT}"; then
  echo "Facebook Group RPA tool is already listening on http://127.0.0.1:${PORT}"
  echo "Health check: curl http://127.0.0.1:${PORT}/health"
  exit 0
fi

cd "${TARGET_DIR}"
export FACEBOOK_RPA_HEADLESS="${FACEBOOK_RPA_HEADLESS:-true}"
npm run start
