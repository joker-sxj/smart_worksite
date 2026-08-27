#!/usr/bin/env python3
"""Benchmark local OpenAI-compatible model endpoints without third-party packages."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import os
import platform
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable

SCHEMA_VERSION = 1
DEFAULT_PROFILE = "deploy/model-profiles/a6000x2-production-32k.env.example"


def load_profile(path: str | Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def build_chat_request(model: str, requested_input_tokens: int, max_tokens: int = 64) -> tuple[dict[str, Any], dict[str, int]]:
    if requested_input_tokens <= 0:
        raise ValueError("requested input tokens must be positive")
    # Repeated short ASCII words provide a portable, tokenizer-independent approximation.
    prompt = "safety " * requested_input_tokens
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "Answer with a short confirmation only."},
            {"role": "user", "content": prompt + "\nConfirm that the context was received."},
        ],
        "temperature": 0,
        "max_tokens": max_tokens,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    return payload, {"requestedInputTokens": requested_input_tokens, "promptCharacters": len(prompt)}


def parse_chat_stream(lines: Iterable[bytes | str], started_at: float, clock: Callable[[], float] = time.monotonic) -> dict[str, Any]:
    text_parts: list[str] = []
    first_token_at: float | None = None
    usage: dict[str, Any] = {}
    for raw_line in lines:
        line = raw_line.decode("utf-8", errors="replace") if isinstance(raw_line, bytes) else raw_line
        line = line.strip()
        if not line or not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            break
        event = json.loads(data)
        event_usage = event.get("usage")
        if isinstance(event_usage, dict):
            usage = event_usage
        choices = event.get("choices") or []
        if not choices:
            continue
        content = ((choices[0].get("delta") or {}).get("content"))
        if content:
            observed_at = clock()
            if first_token_at is None:
                first_token_at = observed_at
            text_parts.append(str(content))
    return {
        "text": "".join(text_parts),
        "ttftSeconds": round(first_token_at - started_at, 6) if first_token_at is not None else None,
        "promptTokens": _integer_or_none(usage.get("prompt_tokens")),
        "outputTokens": _integer_or_none(usage.get("completion_tokens")),
    }


def output_tokens_per_second(output_tokens: int | None, ttft_seconds: float | None, duration_seconds: float) -> float | None:
    if not output_tokens or output_tokens <= 1 or ttft_seconds is None:
        return None
    generation_seconds = duration_seconds - ttft_seconds
    if generation_seconds <= 0:
        return None
    return round((output_tokens - 1) / generation_seconds, 3)


def percentile(values: Iterable[float], percentile_value: float) -> float | None:
    ordered = sorted(float(value) for value in values if value is not None)
    if not ordered:
        return None
    if len(ordered) == 1:
        return round(ordered[0], 6)
    rank = (len(ordered) - 1) * percentile_value / 100
    lower = math.floor(rank)
    upper = math.ceil(rank)
    result = ordered[lower] if lower == upper else ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)
    return round(result, 6)


def summarize_by_concurrency(samples: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for sample in samples:
        grouped.setdefault(str(sample.get("concurrency")), []).append(sample)
    summary: dict[str, Any] = {}
    for concurrency, items in sorted(grouped.items(), key=lambda item: int(item[0])):
        passed = [item for item in items if item.get("status") == "PASS"]
        summary[concurrency] = {
            "sampleCount": len(items),
            "passCount": len(passed),
            "ttftSeconds": _metric_summary(passed, "ttftSeconds"),
            "outputTokensPerSecond": _metric_summary(passed, "outputTokensPerSecond"),
            "durationSeconds": _metric_summary(passed, "durationSeconds"),
            "errors": dict(Counter(item.get("errorClass", "UNKNOWN") for item in items if item.get("status") != "PASS")),
        }
    return summary


def classify_error(error: BaseException) -> str:
    text = f"{type(error).__name__}: {error}".lower()
    if "out of memory" in text or "cuda oom" in text:
        return "OOM"
    if isinstance(error, (TimeoutError, socket.timeout)) or "timed out" in text or "timeout" in text:
        return "TIMEOUT"
    if isinstance(error, (ConnectionError, ConnectionResetError)) or any(term in text for term in ("connection reset", "connection refused", "remote end closed")):
        return "RESTART_OR_CONNECTION"
    if isinstance(error, urllib.error.HTTPError) or "http " in text:
        return "HTTP_ERROR"
    return "UNKNOWN"


def capture_gpu_sample() -> dict[str, Any] | None:
    command = [
        "nvidia-smi",
        "--query-gpu=timestamp,index,name,memory.total,memory.used,utilization.gpu",
        "--format=csv,noheader,nounits",
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.SubprocessError):
        return None
    gpus = []
    for line in result.stdout.splitlines():
        parts = [part.strip() for part in line.split(",")]
        if len(parts) != 6:
            continue
        gpus.append({
            "timestamp": parts[0],
            "index": int(parts[1]),
            "name": parts[2],
            "memoryTotalMiB": _integer_or_none(parts[3]),
            "memoryUsedMiB": _integer_or_none(parts[4]),
            "utilizationPercent": _integer_or_none(parts[5]),
        })
    return {"capturedAt": _now(), "gpus": gpus} if gpus else None


def hardware_snapshot() -> dict[str, Any]:
    sample = capture_gpu_sample()
    return {
        "hostname": platform.node(),
        "platform": platform.platform(),
        "python": platform.python_version(),
        "available": sample is not None,
        "gpus": sample["gpus"] if sample else [],
        "gpuSamples": [sample] if sample else [],
    }


def run_chat_sample(chat_url: str, model: str, length: int, concurrency: int, run: int, timeout: float) -> dict[str, Any]:
    payload, metadata = build_chat_request(model, length)
    started = time.monotonic()
    sample: dict[str, Any] = {
        "kind": "chat",
        "length": length,
        "concurrency": concurrency,
        "run": run,
        **metadata,
    }
    try:
        request = _json_request(chat_url, payload)
        with urllib.request.urlopen(request, timeout=timeout) as response:
            parsed = parse_chat_stream(response, started)
        duration = round(time.monotonic() - started, 6)
        sample.update(parsed)
        sample.update({
            "status": "PASS",
            "durationSeconds": duration,
            "outputTokensPerSecond": output_tokens_per_second(parsed["outputTokens"], parsed["ttftSeconds"], duration),
        })
    except BaseException as error:
        sample.update({
            "status": "ERROR",
            "durationSeconds": round(time.monotonic() - started, 6),
            "errorClass": classify_error(error),
            "errorMessage": _safe_error(error),
        })
    return sample


def run_chat_matrix(chat_url: str, model: str, lengths: list[int], concurrencies: list[int], runs: int, timeout: float) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    for length in lengths:
        for concurrency in concurrencies:
            for run in range(1, runs + 1):
                with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
                    futures = [executor.submit(run_chat_sample, chat_url, model, length, concurrency, run, timeout) for _ in range(concurrency)]
                    samples.extend(future.result() for future in futures)
    return samples


def run_smoke(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    started = time.monotonic()
    try:
        with urllib.request.urlopen(_json_request(url, payload), timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
        return {"status": "PASS", "durationSeconds": round(time.monotonic() - started, 6), "responseKeys": sorted(body.keys())}
    except BaseException as error:
        return {"status": "ERROR", "durationSeconds": round(time.monotonic() - started, 6), "errorClass": classify_error(error), "errorMessage": _safe_error(error)}


def build_report(profile: dict[str, str], hardware: dict[str, Any], samples: list[dict[str, Any]], smoke: dict[str, Any], validated_on_host: bool) -> dict[str, Any]:
    hardware = dict(hardware)
    hardware.setdefault("gpuSamples", [])
    errors = Counter(sample.get("errorClass", "UNKNOWN") for sample in samples if sample.get("status") != "PASS")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": _now(),
        "validatedOnHost": bool(validated_on_host),
        "validationNotice": "Customer-host acceptance evidence" if validated_on_host else "Functional benchmark only; not customer A6000 acceptance evidence",
        "profile": {
            "name": profile.get("MODEL_PROFILE_NAME", "unknown"),
            "chatModel": profile.get("CHAT_MODEL_NAME"),
            "maxContextTokens": _integer_or_none(profile.get("CHAT_MAX_MODEL_LEN")),
            "maxSequences": _integer_or_none(profile.get("CHAT_MAX_NUM_SEQS")),
            "tensorParallelSize": _integer_or_none(profile.get("CHAT_TENSOR_PARALLEL_SIZE")),
        },
        "hardware": hardware,
        "samples": samples,
        "summaryByConcurrency": summarize_by_concurrency(samples),
        "smoke": smoke,
        "indicators": {
            "oom": errors.get("OOM", 0) > 0,
            "timeout": errors.get("TIMEOUT", 0) > 0,
            "restartOrConnection": errors.get("RESTART_OR_CONNECTION", 0) > 0,
            "errorCounts": dict(errors),
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Benchmark local smart-worksite model endpoints and write acceptance evidence.")
    parser.add_argument("--profile", default=DEFAULT_PROFILE, help="Model profile env file")
    parser.add_argument("--base-url", help="Chat OpenAI-compatible base URL or chat completions URL")
    parser.add_argument("--lengths", default="2000,8000,16000,24000,32000", help="Approximate input token targets")
    parser.add_argument("--concurrency", default="1,2", help="Comma-separated concurrent request counts")
    parser.add_argument("--runs", type=int, default=3, help="Batches per length/concurrency")
    parser.add_argument("--timeout", type=float, default=300, help="Per-request timeout seconds")
    parser.add_argument("--output", required=True, help="JSON report output path")
    parser.add_argument("--validated-on-host", action="store_true", help="Mark only when run on the intended customer acceptance host")
    args = parser.parse_args(argv)

    profile = load_profile(args.profile)
    lengths = _positive_csv(args.lengths, "lengths")
    concurrencies = _positive_csv(args.concurrency, "concurrency")
    if args.runs <= 0 or args.timeout <= 0:
        parser.error("--runs and --timeout must be positive")

    chat_url = _chat_url(args.base_url, profile)
    embedding_url = f"http://127.0.0.1:{profile.get('EMBEDDING_HOST_PORT', '18001')}/v1/embeddings"
    rerank_url = f"http://127.0.0.1:{profile.get('RERANK_HOST_PORT', '18002')}/rerank"
    hardware = hardware_snapshot()
    samples = run_chat_matrix(chat_url, profile.get("CHAT_MODEL_NAME", "smart-worksite-chat"), lengths, concurrencies, args.runs, args.timeout)
    final_gpu = capture_gpu_sample()
    if final_gpu:
        hardware["gpuSamples"].append(final_gpu)
    smoke = {
        "embedding": run_smoke(embedding_url, {"model": profile.get("EMBEDDING_MODEL_NAME"), "input": ["construction safety benchmark"]}, args.timeout),
        "reranker": run_smoke(rerank_url, {"model": profile.get("RERANK_MODEL_NAME"), "query": "safety risk", "documents": ["risk closed", "risk unresolved"]}, args.timeout),
    }
    report = build_report(profile, hardware, samples, smoke, args.validated_on_host)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Benchmark report written to {output}")
    print(f"Validated on host: {report['validatedOnHost']}")
    print(f"Errors: {report['indicators']['errorCounts']}")
    return 0 if all(sample.get("status") == "PASS" for sample in samples) and all(item.get("status") == "PASS" for item in smoke.values()) else 1


def _chat_url(base_url: str | None, profile: dict[str, str]) -> str:
    value = base_url or f"http://127.0.0.1:{profile.get('CHAT_HOST_PORT', '18000')}"
    value = value.rstrip("/")
    if value.endswith("/chat/completions"):
        return value
    if value.endswith("/v1"):
        return value + "/chat/completions"
    return value + "/v1/chat/completions"


def _json_request(url: str, payload: dict[str, Any]) -> urllib.request.Request:
    return urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers={"Content-Type": "application/json", "Accept": "application/json"}, method="POST")


def _metric_summary(samples: list[dict[str, Any]], key: str) -> dict[str, Any]:
    values = [sample[key] for sample in samples if sample.get(key) is not None]
    return {"p50": percentile(values, 50), "p95": percentile(values, 95)}


def _positive_csv(value: str, name: str) -> list[int]:
    try:
        parsed = [int(item.strip()) for item in value.split(",") if item.strip()]
    except ValueError as error:
        raise SystemExit(f"{name} must contain integers") from error
    if not parsed or any(item <= 0 for item in parsed):
        raise SystemExit(f"{name} must contain positive integers")
    return parsed


def _integer_or_none(value: Any) -> int | None:
    try:
        return int(value) if value is not None and str(value).strip() else None
    except (TypeError, ValueError):
        return None


def _safe_error(error: BaseException) -> str:
    text = str(error) or type(error).__name__
    return text[:300]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


if __name__ == "__main__":
    raise SystemExit(main())