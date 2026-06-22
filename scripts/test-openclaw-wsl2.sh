#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/.npm-global/bin:$PATH"

CONFIG="$HOME/.openclaw/openclaw.json"
GATEWAY_PORT="${OPENCLAW_GATEWAY_PORT:-18889}"
if [ ! -f "$CONFIG" ]; then
  echo "Missing OpenClaw config: $CONFIG"
  echo "Run ./scripts/setup-openclaw-wsl2.sh first and make sure Ollama is reachable from WSL."
  exit 1
fi

TOKEN="$(node -e "const fs=require('fs'); const c=JSON.parse(fs.readFileSync(process.env.CONFIG || '$CONFIG','utf8')); console.log(c.gateway?.auth?.token || c.gateway?.token || '')")"
if [ -z "$TOKEN" ]; then
  echo "Cannot find gateway token in $CONFIG"
  exit 1
fi

wait_for_gateway() {
  local attempts=45
  local delay=2
  local i
  for ((i=1; i<=attempts; i++)); do
    if curl -fsS --connect-timeout 2 "http://127.0.0.1:${GATEWAY_PORT}/" >/dev/null 2>&1; then
      return 0
    fi
    sleep "${delay}"
  done
  return 1
}

if ! wait_for_gateway; then
  echo "Gateway is not reachable on port ${GATEWAY_PORT}. Restarting service and waiting."
  systemctl --user restart openclaw-gateway.service || true
  if ! wait_for_gateway; then
    echo "Gateway still not reachable on port ${GATEWAY_PORT} after restart."
    systemctl --user status openclaw-gateway.service --no-pager || true
    exit 1
  fi
fi

echo "[1/3] Clearing stale HTTP sessions"
python3 - <<'PY'
import json
import pathlib

base = pathlib.Path.home() / ".openclaw" / "agents" / "main" / "sessions"
index = base / "sessions.json"

if not index.exists():
    print("No sessions.json found, skip cleanup.")
    raise SystemExit(0)

data = json.loads(index.read_text())
removed = []
for key, value in list(data.items()):
    if key.startswith("agent:main:openresponses:") or key.startswith("agent:main:openai:"):
        session_id = value.get("sessionId")
        if session_id:
            for suffix in (".jsonl", ".trajectory.jsonl", ".trajectory-path.json"):
                target = base / f"{session_id}{suffix}"
                if target.exists():
                    target.unlink()
        removed.append(key)
        del data[key]

index.write_text(json.dumps(data, indent=2))
print(f"Removed {len(removed)} stale HTTP session(s).")
PY
systemctl --user restart openclaw-gateway.service
if ! wait_for_gateway; then
  echo "Gateway did not come back after session cleanup restart."
  systemctl --user status openclaw-gateway.service --no-pager || true
  exit 1
fi

echo "[2/3] Testing /v1/responses"
curl -fsS \
  -X POST "http://127.0.0.1:${GATEWAY_PORT}/v1/responses" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"model":"openclaw/default","input":"Reply with exactly OPENCLAW_WSL2_OK and nothing else.","tools":[],"tool_choice":"none"}'
echo

echo "[3/3] Testing /v1/chat/completions"
curl -fsS \
  -X POST "http://127.0.0.1:${GATEWAY_PORT}/v1/chat/completions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"model":"openclaw/default","messages":[{"role":"user","content":"Reply with exactly OPENCLAW_CHAT_OK and nothing else."}],"tools":[],"tool_choice":"none"}'
echo

echo "OpenClaw WSL2 response test finished."
