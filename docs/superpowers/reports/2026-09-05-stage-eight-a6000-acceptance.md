# 第八阶段 Task 5：客户双 RTX A6000 验收证据报告

日期：2026-09-05
验收对象：客户双 NVIDIA RTX A6000 48GB 主机上的本地模型推理
报告边界：本报告只记录当前工作树中可由 Profile、脚本和既有文档复核的事实。客户 A6000 主机尚未提供，因此不把配置声明、H100 结果、单元测试、Mock 或推测写成客户性能验收结论。
工作树：`C:\Users\23883\.codex\worktrees\smart_worksite\stage-three-conversation`

## 1. 结论

客户双 RTX A6000 主机尚未提供，客户侧真实 `nvidia-smi`、服务日志和 benchmark JSON 均缺失。本 Task 5 的客户硬件验收状态为 **BLOCKED**。

以下结论必须全部保持 `BLOCKED`：

- 32K 和 16K 在客户双 A6000 上是否能够加载、完成请求并稳定运行；
- 2K、8K、16K、24K、32K 五个输入长度在并发 1/2 下的实测结果；
- TTFT、输出 `tokens/s`、总耗时的 P50/P95；
- 显存总量、显存使用峰值、GPU 利用率和显存余量；
- OOM、超时、排队/等待、连接重置或服务重启；
- 32K 失败后切换 16K 的必要性、稳定性和回退结论。

已知的 H100 证据只能证明本地调用链、服务 readiness/smoke 和配置门禁的功能环境事实，不能替代任何上述 A6000 结论。报告不使用 Mock，不执行或引用 H100 性能数据作为 A6000 数据。

## 2. 可核验的仓库事实

### 2.1 Profile 声明（不是实机证明）

| Profile | 仓库声明 | 可核验位置 | A6000 实机状态 |
|---|---|---|---|
| `a6000x2-production-32k` | 两张、每张最低 48 GB、型号匹配 `RTX A6000`、`CUDA_VISIBLE_DEVICES=0,1`、TP=2、`CHAT_MAX_MODEL_LEN=32768`、最大并发 2 | `deploy/model-profiles/a6000x2-production-32k.env.example` | **BLOCKED** |
| `a6000x2-stable-16k` | 两张、每张最低 48 GB、型号匹配 `RTX A6000`、`CUDA_VISIBLE_DEVICES=0,1`、TP=2、`CHAT_MAX_MODEL_LEN=16384`、最大并发 1 | `deploy/model-profiles/a6000x2-stable-16k.env.example` | **BLOCKED** |
| `a6000x2-bf16` | 兼容入口；16K BF16 配置，保留现有启动参数兼容性 | `deploy/model-profiles/a6000x2-bf16.env.example` | **BLOCKED** |

上述文件还声明主模型 `Qwen/Qwen3.8-27B`、revision `1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0`，以及 embedding/reranker 模型和镜像 digest。它们是部署配置事实，不证明当前工作树已在客户主机下载、加载或通过验收。

### 2.2 Benchmark 能力和证据边界

真实脚本 `scripts/benchmark-local-models.py`：

- 默认支持长度 `2000,8000,16000,24000,32000`、并发 `1,2`、每组默认 3 次运行；本次客户验收命令显式传参，不依赖默认值。
- 为每个长度 × 并发组合生成样本，并写出 `summaryByLengthAndConcurrency`。
- 记录 TTFT、输出 tokens/s、总耗时的 P50/P95，以及错误分类 `OOM`、`TIMEOUT`、`RESTART_OR_CONNECTION`。
- 采集 `nvidia-smi` 的设备名、总显存、已用显存和 GPU 利用率采样；同时执行 embedding/reranker smoke。
- 只有在目标客户主机上运行时才允许传 `--validated-on-host`；该标志不能由本地或 H100 主机代填。

单元测试 `scripts/benchmark-local-models.tests.py` 只验证请求构造、流解析、统计和错误分类等代码行为，不形成真实模型或硬件验收证据。

### 2.3 H100 功能环境证据

既有文档记录当前/历史远程环境为双 NVIDIA H100 PCIe，约 80 GB/卡；记录了本地 chat/vision、embedding、rerank readiness/smoke 和调用链功能性结果。可复核位置：

- `docs/superpowers/reports/2026-08-27-a6000-local-inference-verification.md`
- `docs/superpowers/reports/2026-09-04-stage-seven-report-enhancement-acceptance.md`
- `docs/本地大模型评测与验收方法.md`

这些文档均明确 H100 不是客户双 A6000 性能验收环境。因此 H100 结果的适用范围仅为：本地调用链、模型服务 readiness/smoke、配置/启动门禁和功能回归。H100 的吞吐、显存、上下文边界、并发、P50/P95、TTFT、tokens/s、OOM、超时、队列和重启结果均不得外推到 A6000。

## 3. 当前阻塞清单、责任方与解除条件

| 验收项 | 状态 | 责任方 | 缺失输入 | 解除条件 |
|---|---|---|---|---|
| 客户双 A6000 硬件身份/显存 | **BLOCKED** | 客户主机提供方 | 可登录 Linux 主机、两张真实 GPU、`nvidia-smi` 原始输出 | 提交原始 `nvidia-smi` 输出，确认两张均为 RTX A6000、每张总显存不少于 48 GB、驱动和可见 GPU 与 Profile 一致 |
| 32K Profile | **BLOCKED** | 客户主机提供方 + 交付验收方 | `a6000x2-production-32k`、真实模型缓存/权重、启动日志、服务健康结果 | 在客户主机启动成功，benchmark JSON 的 profile/hardware/`validatedOnHost` 与主机证据一致；所有 32K × 并发组合有样本和结果 |
| 16K Profile/回退 | **BLOCKED** | 客户主机提供方 + 交付验收方 | `a6000x2-stable-16k`、触发回退的真实原因、重新启动日志和 benchmark JSON | 32K 出现真实 OOM/超时/重启或其他经复核的稳定性阻断时，或客户明确要求验证回退；完成 16K 全矩阵并保留原因链 |
| 2K/8K/16K/24K/32K × 并发 1/2 | **BLOCKED** | 交付验收方在客户主机执行 | 客户 Profile、真实端点、真实输入长度、`--runs 3` 的完整 JSON | 每个长度和并发组合均有完整样本、通过数、错误计数及可回溯时间/主机/Profile |
| TTFT、tokens/s、P50/P95 | **BLOCKED** | 交付验收方 | benchmark 原始 JSON 和脚本版本 | JSON 中每个矩阵格均有 TTFT、output tokens/s、duration 的 P50/P95；人工复核确认无缺失值被误读为通过 |
| 显存/利用率 | **BLOCKED** | 客户主机提供方 + 交付验收方 | benchmark 内 `gpuSamples`、独立 `nvidia-smi` 原始采样 | 采样显示两张目标 GPU，且能与 benchmark 时间窗和 Profile 对齐；不能用 Profile 的 `GPU_MIN_MEMORY_GB` 代替实测峰值 |
| OOM/超时/队列/重启 | **BLOCKED** | 交付验收方；客户主机提供日志 | benchmark 错误样本、容器 `ps`、服务日志、重启计数/时间窗 | 完整矩阵结束后复核错误分类、队列行为和服务重启；OOM/超时/重启必须保留原始日志，不能以“无记录”推断未发生 |

在上述输入齐全前，不能将任一行改为 `PASS`、`FAIL` 或确定的容量承诺。缺失客户主机不是测试失败，而是证据条件未满足，故统一为 `BLOCKED`。

## 4. 客户主机真实执行命令

以下命令是解除阻塞后在客户 Linux 主机执行的真实取证命令。当前没有客户主机，故本报告不伪造执行时间、输出或 JSON。

### 4.1 32K 目标档

```bash
set -euo pipefail
set -a
source deploy/model-profiles/a6000x2-production-32k.env.example
set +a

HOST="${HOST:-$(hostname -s)}"
OUT_DIR="${OUT_DIR:-reports/2026-09-05-a6000-production-32k-${HOST}}"
mkdir -p "$OUT_DIR"

COMPOSE=(docker compose -f deploy/docker-compose-env.yml -f deploy/docker-compose-models.yml \
  --env-file deploy/.env \
  --env-file deploy/model-profiles/a6000x2-production-32k.env.example)

collect_gpu_sample() {
  nvidia-smi --query-gpu=timestamp,index,name,memory.total,memory.used,utilization.gpu,driver_version \
    --format=csv,noheader,nounits
}

collect_container_state() {
  local phase="$1"
  printf 'phase=%s\n' "$phase" > "$OUT_DIR/containers-${phase}.txt"
  for service in local-llm local-embedding local-reranker; do
    container_id=$("${COMPOSE[@]}" ps -q "$service")
    if [ -n "$container_id" ]; then
      docker inspect --format "service=$service id={{.Id}} startedAt={{.State.StartedAt}} restartCount={{.RestartCount}} status={{.State.Status}}" "$container_id" \
        >> "$OUT_DIR/containers-${phase}.txt"
    else
      printf 'service=%s id=NOT_FOUND\n' "$service" >> "$OUT_DIR/containers-${phase}.txt"
    fi
  done
}

collect_gpu_sample | tee "$OUT_DIR/nvidia-smi-before.csv"

collect_container_state before

SAMPLE_STARTED_AT="$(date --iso-8601=seconds)"
 : > "$OUT_DIR/nvidia-smi-during.csv"
cleanup_sampling() {
  if kill -0 "$sample_pid" 2>/dev/null; then
    kill "$sample_pid" 2>/dev/null || true
    wait "$sample_pid" 2>/dev/null || true
  fi
}
trap cleanup_sampling EXIT INT TERM

while true; do
  collect_gpu_sample >> "$OUT_DIR/nvidia-smi-during.csv"
  sleep 5
done &
sample_pid=$!

nvidia-smi --query-gpu=timestamp,index,name,memory.total,memory.used,utilization.gpu,driver_version \
  --format=csv,noheader,nounits > /dev/null

./scripts/check-gpu-runtime.sh \
  deploy/model-profiles/a6000x2-production-32k.env.example \
  2>&1 | tee "$OUT_DIR/check-gpu-runtime.log"

./scripts/start-all.sh --model-profile a6000x2-production-32k \
  2>&1 | tee "$OUT_DIR/start-all.log"

"${COMPOSE[@]}" ps | tee "$OUT_DIR/compose-ps-before-benchmark.txt"
collect_container_state before-benchmark

BENCHMARK_STARTED_AT="$(date --iso-8601=seconds)"
printf 'benchmarkStartedAt=%s\n' "$BENCHMARK_STARTED_AT" >> "$OUT_DIR/time-window.txt"
set +e
python3 scripts/benchmark-local-models.py \
  --profile deploy/model-profiles/a6000x2-production-32k.env.example \
  --lengths 2000,8000,16000,24000,32000 \
  --concurrency 1,2 \
  --runs 3 \
  --output "$OUT_DIR/a6000-production-32k-benchmark.json" \
  --validated-on-host \
  2>&1 | tee "$OUT_DIR/benchmark.stdout.log"
benchmark_exit=${PIPESTATUS[0]}
set -e
BENCHMARK_FINISHED_AT="$(date --iso-8601=seconds)"
printf 'benchmarkFinishedAt=%s\n' "$BENCHMARK_FINISHED_AT" >> "$OUT_DIR/time-window.txt"
if [ "$benchmark_exit" -ne 0 ]; then
  printf 'benchmarkExit=%s\n' "$benchmark_exit" >> "$OUT_DIR/time-window.txt"
fi

SAMPLE_FINISHED_AT="$(date --iso-8601=seconds)"
printf 'sampleFinishedAt=%s\n' "$SAMPLE_FINISHED_AT" >> "$OUT_DIR/time-window.txt"
cleanup_sampling
trap - EXIT INT TERM
collect_gpu_sample | tee "$OUT_DIR/nvidia-smi-after.csv"
collect_container_state after
"${COMPOSE[@]}" ps | tee "$OUT_DIR/compose-ps-after-benchmark.txt"
"${COMPOSE[@]}" logs --timestamps --since "$SAMPLE_STARTED_AT" --until "$SAMPLE_FINISHED_AT" \
  local-llm local-embedding local-reranker | tee "$OUT_DIR/model-logs-${SAMPLE_STARTED_AT}-to-${SAMPLE_FINISHED_AT}.txt"

printf 'queueEvidence=benchmark records request duration and concurrency; it does not expose queue depth.\n' \
  | tee "$OUT_DIR/queue-evidence.txt"
exit "$benchmark_exit"
```

### 4.2 16K 回退档（仅在真实触发条件或客户明确要求时）

```bash
set -euo pipefail
HOST="${HOST:-$(hostname -s)}"
OUT_DIR="${OUT_DIR:-reports/2026-09-05-a6000-stable-16k-${HOST}}"
mkdir -p "$OUT_DIR"
set -a
source deploy/model-profiles/a6000x2-stable-16k.env.example
set +a

COMPOSE=(docker compose -f deploy/docker-compose-env.yml -f deploy/docker-compose-models.yml \
  --env-file deploy/.env \
  --env-file deploy/model-profiles/a6000x2-stable-16k.env.example)
collect_gpu_sample() {
  nvidia-smi --query-gpu=timestamp,index,name,memory.total,memory.used,utilization.gpu,driver_version \
    --format=csv,noheader,nounits
}
collect_container_state() {
  local phase="$1"
  printf 'phase=%s\n' "$phase" > "$OUT_DIR/containers-${phase}.txt"
  for service in local-llm local-embedding local-reranker; do
    container_id=$("${COMPOSE[@]}" ps -q "$service")
    if [ -n "$container_id" ]; then
      docker inspect --format "service=$service id={{.Id}} startedAt={{.State.StartedAt}} restartCount={{.RestartCount}} status={{.State.Status}}" "$container_id" \
        >> "$OUT_DIR/containers-${phase}.txt"
    else
      printf 'service=%s id=NOT_FOUND\n' "$service" >> "$OUT_DIR/containers-${phase}.txt"
    fi
  done
}

collect_gpu_sample | tee "$OUT_DIR/nvidia-smi-before.csv"
./scripts/check-gpu-runtime.sh deploy/model-profiles/a6000x2-stable-16k.env.example \
  2>&1 | tee "$OUT_DIR/check-gpu-runtime.log"
./scripts/start-all.sh --model-profile a6000x2-stable-16k \
  2>&1 | tee "$OUT_DIR/start-all.log"
"${COMPOSE[@]}" ps | tee "$OUT_DIR/compose-ps-before-benchmark.txt"
collect_container_state before-benchmark

SAMPLE_STARTED_AT="$(date --iso-8601=seconds)"
printf 'benchmarkStartedAt=%s\n' "$SAMPLE_STARTED_AT" > "$OUT_DIR/time-window.txt"
while true; do
  collect_gpu_sample >> "$OUT_DIR/nvidia-smi-during.csv"
  sleep 5
done &
sample_pid=$!
cleanup_sampling() {
  if kill -0 "$sample_pid" 2>/dev/null; then
    kill "$sample_pid" 2>/dev/null || true
    wait "$sample_pid" 2>/dev/null || true
  fi
}
trap cleanup_sampling EXIT INT TERM

set +e
python3 scripts/benchmark-local-models.py \
  --profile deploy/model-profiles/a6000x2-stable-16k.env.example \
  --lengths 2000,8000,16000,24000,32000 \
  --concurrency 1,2 \
  --runs 3 \
  --output "$OUT_DIR/a6000-stable-16k-benchmark.json" \
  --validated-on-host \
  2>&1 | tee "$OUT_DIR/benchmark.stdout.log"
benchmark_exit=${PIPESTATUS[0]}
set -e
BENCHMARK_FINISHED_AT="$(date --iso-8601=seconds)"
printf 'benchmarkFinishedAt=%s\n' "$BENCHMARK_FINISHED_AT" >> "$OUT_DIR/time-window.txt"
cleanup_sampling
trap - EXIT INT TERM
printf 'sampleFinishedAt=%s\n' "$(date --iso-8601=seconds)" >> "$OUT_DIR/time-window.txt"
collect_gpu_sample | tee "$OUT_DIR/nvidia-smi-after.csv"
collect_container_state after
"${COMPOSE[@]}" ps | tee "$OUT_DIR/compose-ps-after-benchmark.txt"
"${COMPOSE[@]}" logs --timestamps --since "$SAMPLE_STARTED_AT" --until "$BENCHMARK_FINISHED_AT" \
  local-llm local-embedding local-reranker | tee "$OUT_DIR/model-logs-${SAMPLE_STARTED_AT}-to-${BENCHMARK_FINISHED_AT}.txt"
printf 'queueEvidence=benchmark records request duration and concurrency; it does not expose queue depth.\n' \
  | tee "$OUT_DIR/queue-evidence.txt"
exit "$benchmark_exit"
```

16K Profile 的 `CHAT_MAX_MODEL_LEN=16384` 意味着 24K/32K 组合必须保留真实失败/拒绝/超时证据，不能把 16K 回退档的较短上下文结果写成 24K/32K 通过。回退是否满足客户要求，须由客户和验收方依据真实证据单独签字确认。

## 5. 原始产物清单

### 5.1 当前仓库中已存在、可复核的依据

| 产物 | 用途 | 结论边界 |
|---|---|---|
| `deploy/model-profiles/a6000x2-production-32k.env.example` | 32K A6000 目标配置 | 配置声明，不是实测 |
| `deploy/model-profiles/a6000x2-stable-16k.env.example` | 16K A6000 稳定回退配置 | 配置声明，不是实测 |
| `deploy/model-profiles/a6000x2-bf16.env.example` | 兼容 Profile | 配置声明，不是实测 |
| `scripts/benchmark-local-models.py` | 真实主机 benchmark 运行器 | 可生成证据，当前未产生客户 JSON |
| `scripts/benchmark-local-models.tests.py` | benchmark 代码行为测试 | 不构成真实硬件证据 |
| `docs/本地大模型评测与验收方法.md` | 指标、十九类矩阵和取证要求 | 方法依据 |
| `docs/本地大模型选型与数据治理说明.md` | Profile/硬件边界和阻塞清单 | 方法依据 |
| `docs/superpowers/reports/2026-08-27-a6000-local-inference-verification.md` | H100 功能环境与 A6000 缺口 | 不能替代 A6000 性能 |

### 5.2 客户主机应提交的原始产物

以下是预期路径，不代表当前工作树已存在；当前缺失即为本报告 `BLOCKED` 的直接证据：

```text
reports/2026-09-05-a6000-production-32k-${HOST}/nvidia-smi-before.csv
reports/2026-09-05-a6000-production-32k-${HOST}/nvidia-smi-during.csv
reports/2026-09-05-a6000-production-32k-${HOST}/time-window.txt
reports/2026-09-05-a6000-production-32k-${HOST}/check-gpu-runtime.log
reports/2026-09-05-a6000-production-32k-${HOST}/start-all.log
reports/2026-09-05-a6000-production-32k-${HOST}/compose-ps-before-benchmark.txt
reports/2026-09-05-a6000-production-32k-${HOST}/compose-ps-before-benchmark.txt
reports/2026-09-05-a6000-production-32k-${HOST}/compose-ps-after-benchmark.txt
reports/2026-09-05-a6000-production-32k-${HOST}/containers-before-benchmark.txt
reports/2026-09-05-a6000-production-32k-${HOST}/containers-after.txt
reports/2026-09-05-a6000-production-32k-${HOST}/a6000-production-32k-benchmark.json
reports/2026-09-05-a6000-production-32k-${HOST}/benchmark.stdout.log
reports/2026-09-05-a6000-production-32k-${HOST}/model-logs-${BENCHMARK_STARTED_AT}-to-${BENCHMARK_FINISHED_AT}.txt
reports/2026-09-05-a6000-production-32k-${HOST}/nvidia-smi-after.csv
reports/2026-09-05-a6000-production-32k-${HOST}/queue-evidence.txt
reports/2026-09-05-a6000-stable-16k-${HOST}/nvidia-smi-before.csv
reports/2026-09-05-a6000-stable-16k-${HOST}/nvidia-smi-during.csv
reports/2026-09-05-a6000-stable-16k-${HOST}/nvidia-smi-after.csv
reports/2026-09-05-a6000-stable-16k-${HOST}/time-window.txt
reports/2026-09-05-a6000-stable-16k-${HOST}/check-gpu-runtime.log
reports/2026-09-05-a6000-stable-16k-${HOST}/start-all.log
reports/2026-09-05-a6000-stable-16k-${HOST}/compose-ps-before-benchmark.txt
reports/2026-09-05-a6000-stable-16k-${HOST}/compose-ps-after-benchmark.txt
reports/2026-09-05-a6000-stable-16k-${HOST}/containers-before-benchmark.txt
reports/2026-09-05-a6000-stable-16k-${HOST}/containers-after.txt
reports/2026-09-05-a6000-stable-16k-${HOST}/a6000-stable-16k-benchmark.json
reports/2026-09-05-a6000-stable-16k-${HOST}/benchmark.stdout.log
reports/2026-09-05-a6000-stable-16k-${HOST}/model-logs-${BENCHMARK_STARTED_AT}-to-${BENCHMARK_FINISHED_AT}.txt
reports/2026-09-05-a6000-stable-16k-${HOST}/queue-evidence.txt
```

当前没有可引用的客户 A6000 benchmark JSON；特别是不能把 `reports/a6000-production-benchmark.json` 这个示例路径当作已生成文件。

## 6. 十九类验收矩阵适用性

以下逐类登记适用性。`适用`表示该类需要在客户 A6000 验收中执行；`当前状态`表示本报告在缺少客户主机时的证据状态，不表示该类功能已经失败。

| # | 矩阵类别 | 对 A6000 Task 5 的适用性 | 当前状态 | 需要的真实输入/证据 |
|---:|---|---|---|---|
| 1 | `normal` | 适用：标准短/长输入和正常服务请求 | **BLOCKED** | 客户主机、Profile、benchmark JSON |
| 2 | `boundary` | 适用：2K/8K/16K/24K/32K 长度边界及 16K/32K Profile | **BLOCKED** | 五种长度的样本、响应和错误分类 |
| 3 | `empty result` | 适用：服务空响应/无有效 completion 的处理 | **BLOCKED** | 真实端点响应、服务日志 |
| 4 | `partial evidence` | 适用：部分矩阵格通过、部分格失败时是否保留证据 | **BLOCKED** | 完整 JSON 和失败日志 |
| 5 | `no evidence` | 适用：无 A6000 数据时禁止编造通过结论 | **BLOCKED** | 客户硬件和原始产物；本报告已明确不外推 |
| 6 | `format variants` | 适用性有限：Task 5 关注文本 benchmark；输入格式不是本任务性能结论 | **N/A（性能矩阵）** | 如扩展到业务功能，另按功能验收文档执行 |
| 7 | `long input` | 适用：24K/32K 及上下文预算稳定性 | **BLOCKED** | 真实长输入样本、TTFT、显存、错误日志 |
| 8 | `multi-turn` | 适用性有限：benchmark 为独立请求，不证明会话业务行为 | **N/A（性能矩阵）** | 多轮业务功能需使用 H100 功能验收/另行客户回归 |
| 9 | `refresh recovery` | 不适用于 benchmark 进程本身 | **N/A（性能矩阵）** | 属于前端/异步任务验收 |
| 10 | `concurrent clicks` | 不适用于 benchmark 请求发起器；并发请求负载仍由并发 1/2 覆盖 | **N/A（交互）** | 并发负载由 benchmark 线程和服务日志证明 |
| 11 | `permission isolation` | 不适用于模型容量 benchmark | **N/A（性能矩阵）** | 属于业务权限验收 |
| 12 | `disabled resources` | 不适用于模型容量 benchmark | **N/A（性能矩阵）** | 属于业务资源状态验收 |
| 13 | `async status` | 适用性有限：需记录启动/健康/benchmark 终态，但不验证业务任务状态机 | **BLOCKED（运行状态）** | compose 状态、启动日志、benchmark 退出状态 |
| 14 | `database failure` | 不适用于模型硬件 benchmark | **N/A（性能矩阵）** | 属于数据库问答验收 |
| 15 | `model failure` | 适用：OOM、超时、连接重置、空响应和模型重启 | **BLOCKED** | benchmark 错误分类、容器日志、重启计数 |
| 16 | `retrieval degradation` | 不适用于无 RAG 输入的基准请求 | **N/A（性能矩阵）** | 属于 RAG 功能验收 |
| 17 | `timeout` | 适用：每请求超时及服务超时行为 | **BLOCKED** | benchmark `TIMEOUT` 计数、超时配置、日志 |
| 18 | `download contents` | 不适用于模型性能采样 | **N/A（性能矩阵）** | 属于报告/文件下载验收 |
| 19 | `mobile layout` | 不适用于服务端 GPU benchmark | **N/A（性能矩阵）** | 属于前端移动视口验收 |

矩阵中的 `N/A` 仅表示该类别不适用于本独立性能功能点，不得被解释为整个系统的通过结论；适用的类别在客户主机证据到齐前均保持 `BLOCKED`。

## 7. 本地校验记录与禁止事项

本报告新增后只允许执行文档/命令校验，不启动模型、不伪造客户结果、不修改业务代码。应执行并记录：

```bash
python3 scripts/benchmark-local-models.py --help
python3 scripts/benchmark-local-models.tests.py
bash scripts/model-profile-contract.tests.sh
bash scripts/local-inference-docs.tests.sh
bash -n scripts/check-gpu-runtime.sh scripts/check-local-models.sh scripts/start-all.sh scripts/status.sh
git diff --check
```

这些校验只能证明脚本、Profile 合同、文档合同和差异格式；不能解除客户 A6000 阻塞。禁止：

1. 在 H100 上运行 benchmark 后标记 `--validated-on-host`，或把 H100 数值写入 A6000 表格。
2. 用单元测试、Mock、合成输入、Profile 的 48 GB 声明或模型宣传资料填充客户性能字段。
3. 只有命令而没有原始 JSON、`nvidia-smi`、compose 状态和日志时，把结果改为 `PASS`。
4. 在没有真实 32K 失败证据前，擅自宣称必须回退 16K；也不能在没有 16K 实测证据前宣称回退可用。

## 8. 解除阻塞后的报告更新要求

客户主机提供后，责任方应把主机标识（不含凭据）、执行时间、Profile、模型 revision、真实输入性质、命令退出状态和上述原始产物加入本报告或关联不可覆盖的报告。验收方逐格检查五种长度 × 两种并发，复核 TTFT/tokens/s/总耗时 P50/P95、显存采样、OOM/超时/队列/重启和 embedding/reranker smoke；只有证据完整且人工复核通过的格子才能填写 `PASS` 或 `FAIL`。

在这些原始产物进入验收记录前，本报告的最终状态保持：**客户双 RTX A6000 验收 BLOCKED；H100 仅功能环境证据，不替代 A6000 结论。**
