#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ocr="$root/src/main/java/com/xd/smartworksite/ocr/API.md"
runbook="$root/docs/superpowers/runbooks/a6000-local-inference-operations.md"

if grep -Eqi 'QWEN_VL_ENDPOINT=https://dashscope|Qwen VL 凭据必须|QWEN_VL_API_KEY.*为空.*FAILED' "$ocr"; then
  echo 'OCR guide still presents cloud inference as the production path.' >&2
  exit 1
fi
for pattern in 'Java.*Python' 'LOCAL_ONLY' 'a6000x2-production-32k' 'a6000x2-stable-16k' 'POLICY_CRAWLER_NETWORK_ENABLED' 'benchmark-local-models.py' 'validated-on-host' '32K' '16K'; do
  grep -Eq "$pattern" "$runbook" || { echo "Runbook missing: $pattern" >&2; exit 1; }
done
if grep -Eqi '(api[_ -]?key|authorization)[=:][^[:space:]`]*[A-Za-z0-9]{12,}' "$runbook"; then
  echo 'Runbook appears to contain a secret.' >&2
  exit 1
fi
echo 'Local inference documentation contracts passed.'