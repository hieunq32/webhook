#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${FACEBOOK_RPA_TOOL_HOME:-$HOME/.recruitment-rpa/facebook-group-tool}"

if [ ! -f "${TARGET_DIR}/package.json" ]; then
  echo "Missing RPA tool at ${TARGET_DIR}."
  echo "Run ./scripts/setup-facebook-group-rpa-wsl.sh first."
  exit 1
fi

cd "${TARGET_DIR}"
npm run start
