#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${PROJECT_DIR}/rpa/facebook-group-tool"
TARGET_DIR="${FACEBOOK_RPA_TOOL_HOME:-$HOME/.recruitment-rpa/facebook-group-tool}"

echo "[1/4] Syncing Facebook Group RPA tool to Linux filesystem"
mkdir -p "${TARGET_DIR}"
rsync -a --delete \
  --exclude node_modules \
  --exclude storage \
  --exclude artifacts \
  "${SOURCE_DIR}/" "${TARGET_DIR}/"

cd "${TARGET_DIR}"

echo "[2/4] Installing npm dependencies"
npm install

echo "[3/4] Installing Playwright Chromium"
npm run install:browsers

echo "[4/4] Setup completed"
echo "Tool home: ${TARGET_DIR}"
echo "Next login command:"
echo "  cd ${TARGET_DIR} && npm run login"
echo "Next start command:"
echo "  cd ${TARGET_DIR} && npm run start"
