# A6000 本地推理阶段验证报告

日期：2026-08-27
分支：`codex/a6000-local-inference-design`

## 结论

第一阶段已完成代码、配置、门禁、运行状态和本地模型调用链改造，并在远程服务器上完成自动化回归与功能性验证。远程服务器实际识别为两张 NVIDIA H100 PCIe（约 80GB/卡），不是客户目标的两张 RTX A6000 48GB，因此本报告不把 H100 结果作为客户 A6000 的性能验收结论。

生产目标仍为：双 RTX A6000、无 NVLink 依赖、32K 上下文目标；出现显存或稳定性不足时回退到 16K 稳定配置。所有模型推理走本地模型服务，爬虫联网开关与模型推理解耦。

## 已验证内容

- Python AI 服务全量测试：`128 passed in 2.09s`。
- Java 测试与跳过测试打包均通过；Java 业务侧未发现活动中的 DashScope、OpenAI 公网或 Qwen 直连调用。
- 模型 profile 合约、GPU/启动门禁、文档合约、benchmark 单元测试均通过。
- Bash 语法检查和 `git diff --check` 通过。
- H100 主机上的 chat/vision、embedding、rerank readiness 和 smoke 均通过；chat 边界按当前运行 profile 验证为 16K。
- 健康检查会区分本地模型配置与可达性，失败时返回 DOWN/DEGRADED，不泄露 endpoint、API key 或 Authorization。
- 启动脚本会执行模型生成、embedding、rerank smoke，不再仅检查 `/v1/models`。

## 尚未完成的验收项

客户双 A6000 实机尚未接入，因此以下内容不能在当前主机代验：

- 32K profile 在 RTX A6000 48GB 上的实际加载、显存峰值和稳定性。
- 并发 1/2 下的 TTFT、输出 tokens/s、P50/P95、队列和重启行为。
- 32K 失败时是否必须切换 16K profile。

## 客户 A6000 正式验收命令

```bash
./scripts/start-all.sh --model-profile a6000x2-production-32k

python3 scripts/benchmark-local-models.py \
  --profile deploy/model-profiles/a6000x2-production-32k.env.example \
  --lengths 2000,8000,16000,24000,32000 \
  --concurrency 1,2 \
  --runs 3 \
  --output reports/a6000-production-benchmark.json \
  --validated-on-host
```

若 32K 在客户机出现 OOM、超时、模型重启或连续 smoke 失败，执行：

```bash
./scripts/start-all.sh --model-profile a6000x2-stable-16k
```

并保留 benchmark JSON、`docker compose ps`、模型日志和 `nvidia-smi` 采样作为验收证据。没有客户机实测数据前，不承诺固定吞吐量。

## 当前 Git 状态

本阶段业务改动已形成独立提交；仓库中仅保留用户明确要求不要提交的两个备份文件：

- `deploy/docker-compose-models.yml.bak-20260821`
- `deploy/model-profiles/h100-fp8.env.example.bak-20260821`

后续 push 和向 `suppermaker/smart_worksite` 提交 PR，应在用户确认本阶段报告及客户 A6000 验收策略后执行。