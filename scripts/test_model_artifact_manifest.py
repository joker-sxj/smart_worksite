import json
import subprocess
import sys
from pathlib import Path


SCRIPT = Path(__file__).with_name("model-artifact-manifest.py")
REVISION = "1" * 40


def run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args], text=True, capture_output=True, check=False
    )


def create_snapshot(cache: Path, model_id: str, content: bytes = b"weights") -> Path:
    owner, name = model_id.split("/", 1)
    snapshot = cache / "hub" / f"models--{owner}--{name}" / "snapshots" / REVISION
    snapshot.mkdir(parents=True)
    (snapshot / "config.json").write_text("{}", encoding="utf-8")
    (snapshot / "tokenizer.json").write_text("{}", encoding="utf-8")
    (snapshot / "model.safetensors").write_bytes(content)
    return snapshot


def identity_args() -> list[str]:
    return [
        "--expected-profile", "test-profile",
        "--expected-image", "vllm:test@sha256:" + "a" * 64,
        "--expected-runtime", "CHAT_MODEL_NAME=smart-worksite-chat",
        "--expected-runtime", "CHAT_MAX_MODEL_LEN=16384",
        "--expected-model", f"CHAT=Qwen/Test-Chat@{REVISION}",
        "--expected-model", f"EMBEDDING=Qwen/Test-Embedding@{REVISION}",
        "--expected-model", f"RERANK=Qwen/Test-Reranker@{REVISION}",
    ]


def test_manifest_round_trip_and_detects_file_changes(tmp_path: Path):
    cache = tmp_path / "cache"
    snapshots = {
        model: create_snapshot(cache, model)
        for model in ("Qwen/Test-Chat", "Qwen/Test-Embedding", "Qwen/Test-Reranker")
    }
    manifest = tmp_path / "manifest.json"

    generated = run("generate", "--cache-root", str(cache), "--output", str(manifest), *identity_args())
    assert generated.returncode == 0, generated.stderr
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    assert payload["schemaVersion"] == 1
    assert payload["profile"] == "test-profile"
    assert payload["runtimeConfig"]["CHAT_MODEL_NAME"] == "smart-worksite-chat"
    assert payload["provenance"] == {
        "type": "LOCAL_CACHE_BASELINE", "upstreamSignatureVerified": False
    }
    assert len(payload["models"]) == 3
    assert all(item["files"] for item in payload["models"])

    verified = run("verify", "--cache-root", str(cache), "--manifest", str(manifest), *identity_args())
    assert verified.returncode == 0, verified.stderr

    (snapshots["Qwen/Test-Chat"] / "model.safetensors").write_bytes(b"tamper!")
    changed = run("verify", "--cache-root", str(cache), "--manifest", str(manifest), *identity_args())
    assert changed.returncode != 0
    assert "checksum mismatch" in changed.stderr


def test_manifest_rejects_missing_unexpected_and_identity_mismatch(tmp_path: Path):
    cache = tmp_path / "cache"
    snapshots = {
        model: create_snapshot(cache, model)
        for model in ("Qwen/Test-Chat", "Qwen/Test-Embedding", "Qwen/Test-Reranker")
    }
    manifest = tmp_path / "manifest.json"
    assert run("generate", "--cache-root", str(cache), "--output", str(manifest), *identity_args()).returncode == 0

    (snapshots["Qwen/Test-Embedding"] / "tokenizer.json").unlink()
    missing = run("verify", "--cache-root", str(cache), "--manifest", str(manifest), *identity_args())
    assert missing.returncode != 0
    assert "missing file" in missing.stderr
    (snapshots["Qwen/Test-Embedding"] / "tokenizer.json").write_text("{}", encoding="utf-8")

    (snapshots["Qwen/Test-Reranker"] / "unexpected.bin").write_bytes(b"extra")
    unexpected = run("verify", "--cache-root", str(cache), "--manifest", str(manifest), *identity_args())
    assert unexpected.returncode != 0
    assert "unexpected file" in unexpected.stderr

    wrong_identity = identity_args()
    wrong_identity[1] = "other-profile"
    mismatch = run("verify", "--cache-root", str(cache), "--manifest", str(manifest), *wrong_identity)
    assert mismatch.returncode != 0
    assert "profile mismatch" in mismatch.stderr


def test_manifest_rejects_path_traversal(tmp_path: Path):
    cache = tmp_path / "cache"
    for model in ("Qwen/Test-Chat", "Qwen/Test-Embedding", "Qwen/Test-Reranker"):
        create_snapshot(cache, model)
    manifest = tmp_path / "manifest.json"
    assert run("generate", "--cache-root", str(cache), "--output", str(manifest), *identity_args()).returncode == 0
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload["models"][0]["files"][0]["path"] = "../outside"
    manifest.write_text(json.dumps(payload), encoding="utf-8")

    result = run("verify", "--cache-root", str(cache), "--manifest", str(manifest), *identity_args())
    assert result.returncode != 0
    assert "unsafe manifest path" in result.stderr


def test_manifest_rejects_snapshot_symlink_outside_model_repository(tmp_path: Path):
    cache = tmp_path / "cache"
    snapshots = {
        model: create_snapshot(cache, model)
        for model in ("Qwen/Test-Chat", "Qwen/Test-Embedding", "Qwen/Test-Reranker")
    }
    outside = tmp_path / "outside.safetensors"
    outside.write_bytes(b"outside")
    (snapshots["Qwen/Test-Chat"] / "outside-link.safetensors").symlink_to(outside)

    result = run(
        "generate", "--cache-root", str(cache), "--output", str(tmp_path / "manifest.json"),
        *identity_args(),
    )

    assert result.returncode != 0
    assert "unsafe snapshot symlink" in result.stderr


def test_manifest_allows_standard_blob_symlink_and_rejects_duplicate_role(tmp_path: Path):
    cache = tmp_path / "cache"
    snapshots = {
        model: create_snapshot(cache, model)
        for model in ("Qwen/Test-Chat", "Qwen/Test-Embedding", "Qwen/Test-Reranker")
    }
    chat_repository = snapshots["Qwen/Test-Chat"].parents[1]
    blob = chat_repository / "blobs" / "abc"
    blob.parent.mkdir(parents=True)
    blob.write_bytes(b"blob")
    (snapshots["Qwen/Test-Chat"] / "linked.safetensors").symlink_to(blob)
    manifest = tmp_path / "manifest.json"

    assert run("generate", "--cache-root", str(cache), "--output", str(manifest), *identity_args()).returncode == 0
    assert run("verify", "--cache-root", str(cache), "--manifest", str(manifest), *identity_args()).returncode == 0
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload["models"].append(dict(payload["models"][0]))
    manifest.write_text(json.dumps(payload), encoding="utf-8")

    duplicate = run("verify", "--cache-root", str(cache), "--manifest", str(manifest), *identity_args())
    assert duplicate.returncode != 0
    assert "duplicate model role" in duplicate.stderr


def test_manifest_rejects_unsafe_model_id_and_non_object_root(tmp_path: Path):
    unsafe = run(
        "generate", "--cache-root", str(tmp_path), "--output", str(tmp_path / "manifest.json"),
        "--expected-profile", "test", "--expected-image", "image@sha256:" + "a" * 64,
        "--expected-runtime", "CHAT_MODEL_NAME=smart-worksite-chat",
        "--expected-model", f"CHAT=../escape@{REVISION}",
    )
    assert unsafe.returncode != 0
    assert "invalid expected model identity" in unsafe.stderr

    manifest = tmp_path / "not-object.json"
    manifest.write_text("[]", encoding="utf-8")
    result = run("verify", "--cache-root", str(tmp_path), "--manifest", str(manifest), *identity_args())
    assert result.returncode != 0
    assert "manifest root must be an object" in result.stderr
