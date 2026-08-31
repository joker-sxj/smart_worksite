# 上下文预算验收与故障处理

## 适用范围

本手册用于双 NVIDIA RTX A6000 48GB 客户环境。32K 是生产目标，16K 是显存或稳定性不足时的回退配置。模型、Tokenizer、Embedding 与 Reranker 均必须使用本地服务。

## 启动 32K 配置

```bash
cd /home/xidian/sjw/smart_worksite
git pull --ff-only
./scripts/start-all.sh --model-profile a6000x2-production-32k
./scripts/status.sh
```

健康接口必须显示：

- `modelReadiness.status=READY`；
- `maxContextTokens=32768`；
- `contextBudget.outputReserveTokens=4096`；
- `contextBudget.safetyReserveTokens=1024`；
- `contextBudget.templateOverheadTokens=256`；
- `contextBudget.countMode` 为本地 Tokenizer、本地模型端点或保守估算模式之一。

接口不得显示 Tokenizer 路径、密钥、用户问题或证据正文。

## 16K 稳定回退

如果 32K 启动出现 OOM、模型重启或持续上下文失败，停止当前模型服务后使用：

```bash
./scripts/start-all.sh --model-profile a6000x2-stable-16k
./scripts/status.sh
```

健康接口应显示窗口 `16384`、输出预留 `3072`、安全预留 `512`、模板开销 `256`。回退只改变容量和并发，不改变本地模型政策、项目权限或知识证据来源。

## 请求验收

1. 在 Chrome 中打开智慧工地系统，每个独立问题先点击“新建会话”。
2. 分别执行普通模型问答、知识库问答和多轮问答。
3. 检查返回消息的 `usage.contextUsage`，确认存在输入估算、历史/证据选择数量、输出预留和计数模式。
4. 构造明显超过 mandatory 预算的问题，确认返回 `VALIDATION_ERROR`，详情码为 `CONTEXT_BUDGET_EXCEEDED`，且响应不包含问题或证据正文。
5. 新建会话后确认旧会话历史未被引用；失败和处理中消息不得进入上下文。

## 日志检查

```bash
docker compose -f deploy/docker-compose-env.yml -f deploy/docker-compose-models.yml ps
docker logs --since 30m smart-worksite-python-ai-service 2>&1 | grep -Ei 'context|token|error|oom|restart'
journalctl --since '30 minutes ago' --no-pager | grep -Ei 'smart-worksite|oom|nvidia'
nvidia-smi
```

验收期间不应出现 vLLM context length 错误、CUDA OOM、容器反复重启、HTTP 5xx 或正文/密钥泄漏。

## Exact Tokenizer 故障

- `LOCAL_ENDPOINT_REQUIRED`：本地 `/tokenize` 不可用时请求必须失败，不允许转公网。
- `LOCAL_ENDPOINT_WITH_ESTIMATED_FALLBACK`：本地精确计数失败时使用保守估算，`contextUsage.countMode=ESTIMATED`。
- `LOCAL_TOKENIZER`：检查配置路径在 Python 容器内存在且只指向本地文件；禁止 Hugging Face 在线下载。

先检查模型 `/v1/models` 与 `/tokenize`，再检查 Python 日志中的脱敏错误。不要把 Tokenizer 路径、用户正文或服务密钥粘贴进工单。
