#!/usr/bin/env bash
set -euo pipefail

echo "[1/4] Testing Ollama local generate"
curl -sS http://127.0.0.1:11434/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3:0.6b","prompt":"Tra loi ngan gon: OpenClaw da chay duoc.","stream":false,"options":{"num_ctx":32768,"think":false}}'
echo

echo "[2/4] Patching OpenClaw config to lightweight Ollama profile"
node <<'EOF'
const fs = require('fs');
const p = process.env.HOME + '/.openclaw/openclaw.json';
const c = JSON.parse(fs.readFileSync(p, 'utf8'));

c.agents = c.agents || {};
c.agents.defaults = c.agents.defaults || {};
c.agents.defaults.model = c.agents.defaults.model || {};
c.agents.defaults.model.primary = 'ollama/qwen3:0.6b';
c.tools = c.tools || {};
c.tools.profile = 'minimal';

const models = (((c.models || {}).providers || {}).ollama || {}).models || [];
for (const model of models) {
  if (['qwen3:0.6b', 'qwen3:4b', 'qwen3.5:latest'].includes(model.id)) {
    model.reasoning = false;
    model.maxTokens = 512;
    model.contextWindow = 32768;
    model.params = { num_ctx: 32768, think: false };
  }
}

fs.writeFileSync(p, JSON.stringify(c, null, 2));
console.log('patched', p);
EOF

echo "[3/4] Restarting OpenClaw gateway"
systemctl --user restart openclaw-gateway.service
sleep 6

echo "[4/4] Verifying running config"
grep -nE 'primary|qwen3:0.6b|qwen3:4b|qwen3.5:latest|reasoning|num_ctx|think|maxTokens|contextWindow' "$HOME/.openclaw/openclaw.json" || true
systemctl --user status openclaw-gateway.service --no-pager || true
