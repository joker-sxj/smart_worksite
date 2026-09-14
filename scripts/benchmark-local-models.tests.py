import importlib.util
import json
import tempfile
import time
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("benchmark-local-models.py")
spec = importlib.util.spec_from_file_location("benchmark_local_models", MODULE_PATH)
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkLocalModelsTest(unittest.TestCase):
    def test_build_chat_request_targets_streaming_and_requested_length(self):
        request, metadata = benchmark.build_chat_request("smart-worksite-chat", 2000, 64)

        self.assertEqual(request["model"], "smart-worksite-chat")
        self.assertTrue(request["stream"])
        self.assertTrue(request["stream_options"]["include_usage"])
        self.assertEqual(request["max_tokens"], 64)
        self.assertEqual(metadata["requestedInputTokens"], 2000)
        self.assertGreater(len(request["messages"][1]["content"]), 4000)
        self.assertNotIn("requested_input_tokens", request)

    def test_parse_stream_records_first_token_and_usage(self):
        lines = [
            b'data: {"choices":[{"delta":{"content":"A"}}]}\n',
            b'data: {"choices":[{"delta":{"content":"B"}}]}\n',
            b'data: {"choices":[],"usage":{"prompt_tokens":2001,"completion_tokens":20}}\n',
            b'data: [DONE]\n',
        ]
        times = iter([10.25, 10.50])

        result = benchmark.parse_chat_stream(lines, started_at=10.0, clock=lambda: next(times))

        self.assertEqual(result["text"], "AB")
        self.assertEqual(result["ttftSeconds"], 0.25)
        self.assertEqual(result["promptTokens"], 2001)
        self.assertEqual(result["outputTokens"], 20)

    def test_output_tokens_per_second_excludes_ttft(self):
        self.assertEqual(benchmark.output_tokens_per_second(21, 0.5, 2.5), 10.0)
        self.assertIsNone(benchmark.output_tokens_per_second(0, 0.5, 2.5))

    def test_summary_reports_client_dispatch_wait(self):
        samples = [
            {"concurrency": 2, "status": "PASS", "clientDispatchWaitSeconds": 0.1, "durationSeconds": 1.0},
            {"concurrency": 2, "status": "PASS", "clientDispatchWaitSeconds": 0.3, "durationSeconds": 1.2},
        ]

        summary = benchmark.summarize_by_concurrency(samples)["2"]

        self.assertEqual(summary["clientDispatchWaitSeconds"]["p50"], 0.2)

    def test_percentile_and_grouping_are_deterministic(self):
        samples = [
            {"concurrency": 1, "status": "PASS", "ttftSeconds": 1.0, "outputTokensPerSecond": 8.0, "durationSeconds": 3.0},
            {"concurrency": 1, "status": "PASS", "ttftSeconds": 2.0, "outputTokensPerSecond": 12.0, "durationSeconds": 5.0},
            {"concurrency": 2, "status": "ERROR", "errorClass": "OOM"},
        ]

        groups = benchmark.summarize_by_concurrency(samples)

        self.assertEqual(benchmark.percentile([1, 2, 3, 4], 95), 3.85)
        self.assertEqual(groups["1"]["sampleCount"], 2)
        self.assertEqual(groups["1"]["ttftSeconds"]["p50"], 1.5)
        self.assertEqual(groups["2"]["errors"]["OOM"], 1)

    def test_error_classification_covers_oom_timeout_restart_and_http(self):
        self.assertEqual(benchmark.classify_error(RuntimeError("CUDA out of memory")), "OOM")
        self.assertEqual(benchmark.classify_error(TimeoutError("timed out")), "TIMEOUT")
        self.assertEqual(benchmark.classify_error(ConnectionResetError("connection reset by peer")), "RESTART_OR_CONNECTION")
        self.assertEqual(benchmark.classify_error(RuntimeError("HTTP 503")), "HTTP_ERROR")

    def test_report_schema_contains_acceptance_evidence(self):
        report = benchmark.build_report(
            profile={
                "MODEL_PROFILE_NAME": "a6000x2-production-32k",
                "CHAT_MODEL_ID": "Qwen/example",
                "CHAT_MODEL_REVISION": "abc123",
                "CHAT_MAX_MODEL_LEN": "32768",
            },
            hardware={"available": True, "gpus": [{"name": "NVIDIA RTX A6000", "memoryTotalMiB": 49140}]},
            samples=[{"status": "PASS", "concurrency": 1, "length": 2000}],
            smoke={"embedding": {"status": "PASS"}, "reranker": {"status": "PASS"}},
            validated_on_host=False,
        )

        self.assertEqual(report["schemaVersion"], 1)
        self.assertFalse(report["validatedOnHost"])
        self.assertEqual(report["profile"]["name"], "a6000x2-production-32k")
        self.assertEqual(report["profile"]["chatModelId"], "Qwen/example")
        self.assertEqual(report["profile"]["chatModelRevision"], "abc123")
        self.assertIn("summaryByConcurrency", report)
        self.assertIn("2000", report["summaryByLengthAndConcurrency"])
        self.assertIn("1", report["summaryByLengthAndConcurrency"]["2000"])
        self.assertIn("indicators", report)
        self.assertIn("gpuSamples", report["hardware"])

    def test_summary_groups_each_length_and_concurrency_pair(self):
        samples = [
            {"length": 2000, "concurrency": 1, "status": "PASS", "ttftSeconds": 1.0, "outputTokensPerSecond": 8.0, "durationSeconds": 3.0},
            {"length": 2000, "concurrency": 2, "status": "ERROR", "errorClass": "TIMEOUT"},
            {"length": 8000, "concurrency": 1, "status": "PASS", "ttftSeconds": 2.0, "outputTokensPerSecond": 6.0, "durationSeconds": 5.0},
        ]

        groups = benchmark.summarize_by_length_and_concurrency(samples)

        self.assertEqual(groups["2000"]["1"]["sampleCount"], 1)
        self.assertEqual(groups["2000"]["2"]["errors"]["TIMEOUT"], 1)
        self.assertEqual(groups["8000"]["1"]["ttftSeconds"]["p50"], 2.0)

    def test_profile_parser_preserves_json_values_and_ignores_comments(self):
        with tempfile.TemporaryDirectory() as tmp:
            profile = Path(tmp) / "profile.env"
            profile.write_text('# comment\nMODEL_PROFILE_NAME=test\nRERANK_HF_OVERRIDES={"a":true}\n', encoding="utf-8")

            values = benchmark.load_profile(profile)

        self.assertEqual(values["MODEL_PROFILE_NAME"], "test")
        self.assertEqual(values["RERANK_HF_OVERRIDES"], '{"a":true}')

    def test_hardware_validation_requires_exact_customer_gpu_inventory(self):
        profile = {
            "GPU_COUNT": "2",
            "GPU_MIN_MEMORY_GB": "48",
            "GPU_EXPECTED_MODEL_REGEX": "RTX A6000",
        }
        valid = {
            "available": True,
            "gpus": [
                {"name": "NVIDIA RTX A6000", "memoryTotalMiB": 49140},
                {"name": "NVIDIA RTX A6000", "memoryTotalMiB": 49140},
            ],
        }

        self.assertEqual(benchmark.validate_hardware(profile, valid), [])
        errors = benchmark.validate_hardware(profile, {
            "available": True,
            "gpus": [{"name": "NVIDIA H100 PCIe", "memoryTotalMiB": 81559}],
        })
        self.assertIn("expected 2 GPUs, found 1", errors)
        self.assertTrue(any("does not match" in error for error in errors))

    def test_prompt_token_evidence_rejects_missing_or_materially_short_inputs(self):
        self.assertEqual(benchmark.validate_prompt_tokens(32000, 32024, 32768), [])
        self.assertTrue(any("missing" in error for error in benchmark.validate_prompt_tokens(32000, None, 32768)))
        self.assertTrue(any("below" in error for error in benchmark.validate_prompt_tokens(32000, 12000, 32768)))
        self.assertTrue(any("exceeds" in error for error in benchmark.validate_prompt_tokens(32000, 33000, 32768)))

    def test_gpu_monitor_samples_during_long_running_operation(self):
        captures = []

        def capture():
            sample = {"capturedAt": str(len(captures)), "gpus": [{"memoryUsedMiB": len(captures) * 100}]}
            captures.append(sample)
            return sample

        monitor = benchmark.GpuMonitor(interval_seconds=0.005, capture=capture)
        monitor.start()
        deadline = time.monotonic() + 0.5
        while len(captures) < 3 and time.monotonic() < deadline:
            time.sleep(0.005)
        samples = monitor.stop()

        self.assertGreaterEqual(len(samples), 3)
        self.assertEqual(benchmark.gpu_peak_summary(samples)["0"]["memoryUsedPeakMiB"], (len(samples) - 1) * 100)

    def test_auxiliary_contention_monitor_collects_embedding_and_reranker_samples(self):
        calls = []

        def run(name):
            calls.append(name)
            return {"status": "PASS", "durationSeconds": 0.01}

        monitor = benchmark.AuxiliaryContentionMonitor(
            interval_seconds=0.005,
            runners={"embedding": lambda: run("embedding"), "reranker": lambda: run("reranker")},
        )
        monitor.start()
        deadline = time.monotonic() + 0.5
        while len(calls) < 4 and time.monotonic() < deadline:
            time.sleep(0.005)
        result = monitor.stop()

        self.assertGreaterEqual(len(result["embedding"]), 2)
        self.assertGreaterEqual(len(result["reranker"]), 2)

    def test_customer_acceptance_requires_successful_auxiliary_contention(self):
        profile = {
            "MODEL_PROFILE_NAME": "a6000x2-production-32k", "GPU_COUNT": "2",
            "GPU_MIN_MEMORY_GB": "48", "GPU_EXPECTED_MODEL_REGEX": "RTX A6000",
            "CHAT_MAX_MODEL_LEN": "32768",
        }
        hardware = {"available": True, "gpus": [
            {"name": "NVIDIA RTX A6000", "memoryTotalMiB": 49140},
            {"name": "NVIDIA RTX A6000", "memoryTotalMiB": 49140},
        ]}
        samples = [{"status": "PASS", "length": 2000, "concurrency": 1, "promptTokens": 2020}]
        smoke = {"embedding": {"status": "PASS"}, "reranker": {"status": "PASS"}}

        result = benchmark.evaluate_acceptance(
            profile, hardware, samples, smoke, True, [2000], [1], 1,
            "a6000x2-production-32k", contention={"embedding": [], "reranker": []},
        )

        self.assertFalse(result["passed"])
        self.assertTrue(any("embedding contention" in error for error in result["errors"]))
        self.assertTrue(any("reranker contention" in error for error in result["errors"]))

    def test_acceptance_gate_checks_active_profile_matrix_and_prompt_evidence(self):
        profile = {
            "MODEL_PROFILE_NAME": "a6000x2-production-32k",
            "GPU_COUNT": "2",
            "GPU_MIN_MEMORY_GB": "48",
            "GPU_EXPECTED_MODEL_REGEX": "RTX A6000",
            "CHAT_MAX_MODEL_LEN": "32768",
        }
        hardware = {
            "available": True,
            "gpus": [
                {"name": "NVIDIA RTX A6000", "memoryTotalMiB": 49140},
                {"name": "NVIDIA RTX A6000", "memoryTotalMiB": 49140},
            ],
            "gpuSamples": [{"capturedAt": "now", "gpus": []}],
        }
        samples = [
            {"status": "PASS", "length": 2000, "concurrency": 1, "promptTokens": 2020},
            {"status": "PASS", "length": 8000, "concurrency": 1, "promptTokens": 8020},
        ]
        smoke = {"embedding": {"status": "PASS"}, "reranker": {"status": "PASS"}}

        result = benchmark.evaluate_acceptance(
            profile, hardware, samples, smoke, True,
            lengths=[2000, 8000], concurrencies=[1], runs=1,
            active_profile="a6000x2-production-32k",
            contention={"embedding": [{"status": "PASS"}], "reranker": [{"status": "PASS"}]},
        )
        self.assertTrue(result["passed"])

        failed = benchmark.evaluate_acceptance(
            profile, hardware, samples[:1], smoke, True,
            lengths=[2000, 8000], concurrencies=[1], runs=1,
            active_profile="a6000x2-stable-16k",
            contention={"embedding": [{"status": "PASS"}], "reranker": [{"status": "PASS"}]},
        )
        self.assertFalse(failed["passed"])
        self.assertTrue(any("active profile" in error for error in failed["errors"]))
        self.assertTrue(any("matrix cell" in error for error in failed["errors"]))


if __name__ == "__main__":
    unittest.main()
