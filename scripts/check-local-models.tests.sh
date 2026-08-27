#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
tmp="$(mktemp -d)"
server_pid=''
cleanup() {
  [[ -n "$server_pid" ]] && kill "$server_pid" 2>/dev/null || true
  [[ -n "$server_pid" ]] && wait "$server_pid" 2>/dev/null || true
  rm -rf "$tmp"
}
trap cleanup EXIT

port="$(python3 - <<'PY'
import socket
s=socket.socket(); s.bind(('127.0.0.1',0)); print(s.getsockname()[1]); s.close()
PY
)"
cat > "$tmp/profile.env" <<EOF
MODEL_PROFILE_NAME=mock-local-models
AI_DEPLOYMENT_MODE=LOCAL_ONLY
MODEL_HEALTH_TIMEOUT_SECONDS=2
CHAT_HOST_PORT=$port
EMBEDDING_HOST_PORT=$port
RERANK_HOST_PORT=$port
CHAT_MODEL_NAME=smart-worksite-chat
EMBEDDING_MODEL_NAME=smart-worksite-embedding
RERANK_MODEL_NAME=smart-worksite-reranker
CHAT_MAX_MODEL_LEN=1024
EOF

python3 - "$port" "$tmp/fail-chat" <<'PY' &
import json, pathlib, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
port=int(sys.argv[1]); fail=pathlib.Path(sys.argv[2])
class Handler(BaseHTTPRequestHandler):
    def reply(self, status, body):
        raw=json.dumps(body).encode(); self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        if self.path == '/health': self.reply(200, {'status':'ok'})
        elif self.path == '/v1/models': self.reply(200, {'data':[{'id':'smart-worksite-chat'},{'id':'smart-worksite-embedding'},{'id':'smart-worksite-reranker'}]})
        else: self.reply(404, {})
    def do_POST(self):
        length=int(self.headers.get('Content-Length','0')); self.rfile.read(length)
        if self.path.endswith('/chat/completions'):
            self.reply(503 if fail.exists() else 200, {'choices':[] if fail.exists() else [{'message':{'content':'ok'}}]})
        elif self.path.endswith('/embeddings'): self.reply(200, {'data':[{'embedding':[0.1]}]})
        elif self.path.endswith('/rerank'): self.reply(200, {'results':[{'index':0,'relevance_score':0.9}]})
        else: self.reply(404,{})
    def log_message(self, *_): pass
ThreadingHTTPServer(('127.0.0.1',port), Handler).serve_forever()
PY
server_pid=$!
for _ in {1..30}; do python3 - "$port" <<'PY' >/dev/null 2>&1 && break || sleep 0.1
import sys, urllib.request
urllib.request.urlopen(f'http://127.0.0.1:{sys.argv[1]}/health', timeout=1)
PY
done

output="$($repo_root/scripts/check-local-models.sh --model-profile "$tmp/profile.env" --smoke)"
grep -q 'chat boundary smoke: PASS' <<<"$output"
grep -q 'embedding smoke: PASS' <<<"$output"
grep -q 'rerank smoke: PASS' <<<"$output"

touch "$tmp/fail-chat"
if "$repo_root/scripts/check-local-models.sh" --model-profile "$tmp/profile.env" --smoke >"$tmp/out" 2>"$tmp/err"; then
  echo 'expected chat boundary failure' >&2
  exit 1
fi
grep -q 'chat boundary smoke: FAILED' "$tmp/err"
grep -q 'docker compose logs local-llm' "$tmp/err"

kill "$server_pid" 2>/dev/null || true
wait "$server_pid" 2>/dev/null || true
server_pid=''
if "$repo_root/scripts/check-local-models.sh" --model-profile "$tmp/profile.env" >"$tmp/stopped-out" 2>"$tmp/stopped-err"; then
  echo 'expected stopped model endpoint failure' >&2
  exit 1
fi
grep -q 'chat: DOWN' "$tmp/stopped-out"
grep -q 'embedding: DOWN' "$tmp/stopped-out"

echo 'Local model smoke contract tests passed.'