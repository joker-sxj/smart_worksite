import importlib.util
import json
import tempfile
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
            profile={"MODEL_PROFILE_NAME": "a6000x2-production-32k", "CHAT_MAX_MODEL_LEN": "32768"},
            hardware={"available": True, "gpus": [{"name": "NVIDIA RTX A6000", "memoryTotalMiB": 49140}]},
            samples=[{"status": "PASS", "concurrency": 1, "length": 2000}],
            smoke={"embedding": {"status": "PASS"}, "reranker": {"status": "PASS"}},
            validated_on_host=False,
        )

        self.assertEqual(report["schemaVersion"], 1)
        self.assertFalse(report["validatedOnHost"])
        self.assertEqual(report["profile"]["name"], "a6000x2-production-32k")
        self.assertIn("summaryByConcurrency", report)
        self.assertIn("indicators", report)
        self.assertIn("gpuSamples", report["hardware"])

    def test_profile_parser_preserves_json_values_and_ignores_comments(self):
        with tempfile.TemporaryDirectory() as tmp:
            profile = Path(tmp) / "profile.env"
            profile.write_text('# comment\nMODEL_PROFILE_NAME=test\nRERANK_HF_OVERRIDES={"a":true}\n', encoding="utf-8")

            values = benchmark.load_profile(profile)

        self.assertEqual(values["MODEL_PROFILE_NAME"], "test")
        self.assertEqual(values["RERANK_HF_OVERRIDES"], '{"a":true}')


if __name__ == "__main__":
    unittest.main()