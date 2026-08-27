# 双 RTX A6000 本地推理运维手册

## 目标与边界

本项目的生产推理边界是客户双 RTX A6000 48GB（默认无 NVLink）。Java 业务服务只调用 Python AI 服务；Python 只调用本机模型端点。`AI_DEPLOYMENT_MODE=LOCAL_ONLY`、`AI_ALLOW_REMOTE_INFERENCE=false` 和 `AI_ALLOW_CLOUD_FALLBACK=false` 是生产硬门禁。网络爬虫由 `POLICY_CRAWLER_NETWORK_ENABLED` 单独控制。

32K 是生产目标，不是未经客户机器实测的承诺；16K 是可切换的稳定回退。模型、vLLM 镜像和 Profile 中的模型 revision 均应保持固定，便于复现。

## 启动

```bash
cd /home/xidian/sjw/smart_worksite
./scripts/start-all.sh --model-profile a6000x2-production-32k
./scripts/status.sh
```

若 32K 边界冒烟失败：

```bash
./scripts/start-all.sh --model-profile a6000x2-stable-16k
./scripts/status.sh
```

启动前检查而不启动服务：

```bash
./scripts/start-all.sh --check --model-profile a6000x2-stable-16k
```

## 状态与日志

`status.sh` 分开显示 `Local model configuration`（Profile、上下文上限、部署模式）和 `Local model readiness`（chat/vision、embedding、rerank 的实际可达性）。Java `/api/system/dependencies/health` 的 `localAi` 同样提供安全状态，不返回 endpoint、密钥或 Authorization。

```bash
docker compose -f deploy/docker-compose-env.yml -f deploy/docker-compose-models.yml --env-file deploy/.env ps

docker compose -f deploy/docker-compose-env.yml -f deploy/docker-compose-models.yml --env-file deploy/.env logs --tail=200 local-llm local-embedding local-reranker
```

常见处理顺序：确认 `nvidia-smi` 可见两张 A6000；确认模型缓存和磁盘空间；确认模型容器没有 OOM；先切换 16K 再复测。不要通过配置公网 endpoint 或 Token 绕过本地门禁。

## 客户验收 Benchmark

功能性验证（不能标记为客户 A6000 验收证据）：

```bash
python3 scripts/benchmark-local-models.py \
  --profile deploy/model-profiles/a6000x2-stable-16k.env.example \
  --lengths 2000,8000 --concurrency 1 --runs 1 \
  --output reports/local-functional-benchmark.json
```

客户双 A6000 机器上的正式验证：

```bash
./scripts/start-all.sh --model-profile a6000x2-production-32k
python3 scripts/benchmark-local-models.py \
  --profile deploy/model-profiles/a6000x2-production-32k.env.example \
  --lengths 2000,8000,16000,24000,32000 --concurrency 1,2 --runs 3 \
  --output reports/a6000-production-benchmark.json --validated-on-host
```

验收报告必须查看：TTFT、输出 tokens/s、总耗时 P50/P95、GPU 显存样本、OOM、超时、连接重置/重启、Embedding/Rerank 冒烟结果，以及 `validatedOnHost`。只有客户 A6000 主机生成的报告才能作为 32K 结论；H100 只能做功能和门禁验证，不能替代 A6000 性能验收。

## 爬虫网络开关

爬虫和模型推理是两个开关。默认关闭网络：

```env
POLICY_CRAWLER_NETWORK_ENABLED=false
```

启用前确认来源地址、合规范围和访问频率，再重启 Python AI 服务；即使爬虫联网，也必须保持模型 `LOCAL_ONLY`。

## OCR / 文档解析

OCR、PDF 解析和文档理解均通过 Python AI 服务进入本地模型。解析记录中的 provider/model 应以服务实际返回值为准。若本地模型不可用，系统应返回可诊断的失败状态，而不是自动调用云端。
