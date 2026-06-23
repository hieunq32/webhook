#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${PROJECT_DIR}/openclaw-plugins"
TARGET_DIR="${OPENCLAW_FACEBOOK_GROUP_PLUGIN_HOME:-$HOME/.recruitment-rpa/openclaw-plugins/facebook-group-rpa}"
OPENCLAW_BIN="${OPENCLAW_BIN:-$HOME/.npm-global/bin/openclaw}"

echo "[1/5] Syncing OpenClaw Facebook Group plugin to Linux filesystem"
mkdir -p "${TARGET_DIR}"
rsync -a --delete \
  --exclude node_modules \
  --exclude dist \
  "${SOURCE_DIR}/" "${TARGET_DIR}/"

cd "${TARGET_DIR}"
chmod 755 "$HOME/.recruitment-rpa" "$HOME/.recruitment-rpa/openclaw-plugins" "${TARGET_DIR}" 2>/dev/null || true
find "${TARGET_DIR}" -type d -exec chmod 755 {} \;
find "${TARGET_DIR}" -type f -exec chmod 644 {} \;

echo "[2/5] Installing plugin dependencies"
npm install
find "${TARGET_DIR}/node_modules/.bin" -maxdepth 1 -type f -exec chmod 755 {} \; 2>/dev/null || true

echo "[3/5] Building plugin"
npm run build

echo "[4/5] Generating and validating OpenClaw plugin metadata"
"${OPENCLAW_BIN}" plugins build --entry ./dist/index.js --root .
"${OPENCLAW_BIN}" plugins validate --entry ./dist/index.js --root .

echo "[5/5] Plugin prepared"
echo "Plugin home: ${TARGET_DIR}"
