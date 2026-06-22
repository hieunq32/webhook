#!/usr/bin/env bash
set -euo pipefail

GATEWAY_PORT="${OPENCLAW_GATEWAY_PORT:-18889}"
PRIMARY_MODEL="${OPENCLAW_PRIMARY_MODEL:-ollama/qwen3:0.6b}"

echo "[1/7] Checking Linux and Node environment"
uname -a
node -v
npm -v

echo "[2/7] Configuring npm global prefix in user home"
mkdir -p "$HOME/.npm-global"
npm config set prefix "$HOME/.npm-global"
if ! grep -q 'HOME/.npm-global/bin' "$HOME/.bashrc"; then
  printf '\nexport PATH="$HOME/.npm-global/bin:$PATH"\n' >> "$HOME/.bashrc"
fi
export PATH="$HOME/.npm-global/bin:$PATH"

echo "[3/7] Installing OpenClaw globally for current WSL user"
npm install -g openclaw@latest
openclaw --version

echo "[4/7] Resolving an Ollama endpoint reachable from WSL"
DEFAULT_GATEWAY_IP="$(ip route | awk '/default/ {print $3; exit}')"
RESOLV_NAMESERVER_IP="$(awk '/nameserver/ {print $2; exit}' /etc/resolv.conf)"
CANDIDATES=(
  "http://127.0.0.1:11434"
  "http://localhost:11434"
  "http://host.docker.internal:11434"
  "http://${DEFAULT_GATEWAY_IP}:11434"
)
if [ -n "${RESOLV_NAMESERVER_IP}" ]; then
  CANDIDATES+=("http://${RESOLV_NAMESERVER_IP}:11434")
fi

OLLAMA_BASE_URL=""
for candidate in "${CANDIDATES[@]}"; do
  echo "Trying ${candidate}"
  if curl -fsS --connect-timeout 3 "${candidate}/api/tags" >/tmp/ollama-tags.json 2>/dev/null; then
    OLLAMA_BASE_URL="${candidate}"
    break
  fi
done

if [ -z "${OLLAMA_BASE_URL}" ]; then
  echo "Cannot reach Ollama from WSL."
  echo "Tried:"
  printf '  %s\n' "${CANDIDATES[@]}"
  echo "Fix on Windows:"
  echo "1. Make sure the Ollama app/server is running."
  echo "2. In Windows PowerShell, test: curl http://127.0.0.1:11434/api/tags"
  echo "3. If Windows localhost works but WSL cannot reach it, restart Ollama with:"
  echo "   setx OLLAMA_HOST 0.0.0.0:11434"
  echo "   Then fully restart the Ollama app/server."
  exit 1
fi

echo "[5/7] Ollama reachable from WSL at ${OLLAMA_BASE_URL}"
cat /tmp/ollama-tags.json
echo

echo "[6/7] Onboarding OpenClaw with Ollama"
export OPENCLAW_GATEWAY_PORT="${GATEWAY_PORT}"
set +e
openclaw onboard --non-interactive \
  --auth-choice ollama \
  --custom-base-url "${OLLAMA_BASE_URL}" \
  --custom-model-id "qwen3:0.6b" \
  --accept-risk --install-daemon --skip-channels --skip-ui
ONBOARD_EXIT=$?
set -e
if [ "${ONBOARD_EXIT}" -ne 0 ]; then
  echo "OpenClaw onboard exited with code ${ONBOARD_EXIT}. Continuing with doctor/start repair."
fi

echo "[6a/7] Enabling HTTP AI endpoints and disabling memory-search dependency"
openclaw config set gateway.http.endpoints.responses.enabled true --strict-json || true
openclaw config set gateway.http.endpoints.chatCompletions.enabled true --strict-json || true
openclaw config set agents.defaults.memorySearch.enabled false --strict-json || true
openclaw config set agents.defaults.model.primary "\"${PRIMARY_MODEL}\"" --strict-json || true
openclaw config set models.providers.ollama.models[1].reasoning false --strict-json || true
openclaw config set models.providers.ollama.models[1].contextWindow 32768 --strict-json || true
openclaw config set models.providers.ollama.models[1].maxTokens 512 --strict-json || true
openclaw config set models.providers.ollama.models[1].params '{"num_ctx":32768,"think":false}' --strict-json || true
openclaw config set models.providers.ollama.models[2].reasoning false --strict-json || true
openclaw config set models.providers.ollama.models[2].contextWindow 32768 --strict-json || true
openclaw config set models.providers.ollama.models[2].maxTokens 512 --strict-json || true
openclaw config set models.providers.ollama.models[2].params '{"num_ctx":32768,"think":false}' --strict-json || true
openclaw config set models.providers.ollama.models[3].reasoning false --strict-json || true
openclaw config set models.providers.ollama.models[3].contextWindow 32768 --strict-json || true
openclaw config set models.providers.ollama.models[3].maxTokens 512 --strict-json || true
openclaw config set models.providers.ollama.models[3].params '{"num_ctx":32768,"think":false}' --strict-json || true

echo "[7/7] Rebinding WSL OpenClaw gateway to port ${GATEWAY_PORT}"
SERVICE_FILE="$HOME/.config/systemd/user/openclaw-gateway.service"
if [ -f "${SERVICE_FILE}" ]; then
  sed -i -E "s/OPENCLAW_GATEWAY_PORT=[0-9]+/OPENCLAW_GATEWAY_PORT=${GATEWAY_PORT}/g" "${SERVICE_FILE}"
  sed -i -E "s/--port [0-9]+/--port ${GATEWAY_PORT}/g" "${SERVICE_FILE}"
fi

systemctl --user daemon-reload || true
systemctl --user stop openclaw-gateway.service >/dev/null 2>&1 || true
pkill -f 'openclaw/dist/index.js gateway' || true
sleep 3

openclaw doctor --fix || true
systemctl --user restart openclaw-gateway.service
sleep 4
systemctl --user status openclaw-gateway.service --no-pager || true

echo "OpenClaw WSL2 setup finished."
echo "Gateway port: ${GATEWAY_PORT}"
echo "Next: run ./scripts/test-openclaw-wsl2.sh inside WSL from the project folder."
