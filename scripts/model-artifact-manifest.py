#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path, PurePosixPath


REVISION_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
MODEL_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def parse_models(values: list[str]) -> dict[str, tuple[str, str]]:
    result: dict[str, tuple[str, str]] = {}
    for value in values:
        try:
            role, identity = value.split("=", 1)
            model_id, revision = identity.rsplit("@", 1)
        except ValueError:
            fail(f"invalid expected model identity: {value}")
        segments = model_id.split("/")
        if (
            not role
            or not MODEL_ID_PATTERN.fullmatch(model_id)
            or any(segment in {".", ".."} for segment in segments)
            or not REVISION_PATTERN.fullmatch(revision)
        ):
            fail(f"invalid expected model identity: {value}")
        if role in result:
            fail(f"duplicate model role: {role}")
        result[role] = (model_id, revision)
    if not result:
        fail("at least one --expected-model is required")
    return result


def parse_runtime(values: list[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        if "=" not in value:
            fail(f"invalid runtime configuration: {value}")
        key, configured = value.split("=", 1)
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in result:
            fail(f"invalid runtime configuration: {key}")
        result[key] = configured
    if not result:
        fail("at least one --expected-runtime is required")
    return dict(sorted(result.items()))


def snapshot_path(cache_root: Path, model_id: str, revision: str) -> Path:
    owner, name = model_id.split("/", 1)
    return cache_root / "hub" / f"models--{owner}--{name}" / "snapshots" / revision


def digest(path: Path) -> str:
    before = path.stat()
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            value.update(chunk)
    after = path.stat()
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
        after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns
    ):
        fail(f"file changed while hashing: {path.name}")
    return value.hexdigest()


def snapshot_files(snapshot: Path, cache_root: Path) -> list[Path]:
    if not snapshot.is_dir():
        fail(f"model snapshot is missing: {snapshot}")
    repository_root = snapshot.parents[1].resolve()
    files = []
    for item in snapshot.rglob("*"):
        if item.is_symlink():
            try:
                target = item.resolve(strict=True)
            except OSError:
                fail(f"broken snapshot symlink: {item.relative_to(snapshot).as_posix()}")
            if not target.is_relative_to(repository_root):
                fail(f"unsafe snapshot symlink: {item.relative_to(snapshot).as_posix()}")
        if item.is_file():
            files.append(item)
    files.sort(key=lambda item: item.relative_to(snapshot).as_posix())
    if not files:
        fail(f"model snapshot has no files: {snapshot}")
    return files


def build_manifest(cache_root: Path, profile: str, image: str, runtime: dict[str, str], models: dict[str, tuple[str, str]]) -> dict:
    entries = []
    for role, (model_id, revision) in sorted(models.items()):
        print(f"Hashing {role} model artifact {model_id}@{revision}...", file=sys.stderr)
        snapshot = snapshot_path(cache_root, model_id, revision)
        files = [
            {
                "path": path.relative_to(snapshot).as_posix(),
                "size": path.stat().st_size,
                "sha256": digest(path),
            }
            for path in snapshot_files(snapshot, cache_root)
        ]
        entries.append({"role": role, "modelId": model_id, "revision": revision, "files": files})
    return {
        "schemaVersion": 1,
        "profile": profile,
        "image": image,
        "provenance": {
            "type": "LOCAL_CACHE_BASELINE",
            "upstreamSignatureVerified": False,
        },
        "runtimeConfig": runtime,
        "models": entries,
    }


def safe_relative_path(value: object) -> PurePosixPath:
    if not isinstance(value, str):
        fail("unsafe manifest path: non-string")
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        fail(f"unsafe manifest path: {value}")
    return path


def verify_manifest(cache_root: Path, manifest: dict, profile: str, image: str, runtime: dict[str, str], models: dict[str, tuple[str, str]]) -> None:
    if not isinstance(manifest, dict):
        fail("manifest root must be an object")
    if manifest.get("schemaVersion") != 1:
        fail("unsupported model manifest schema")
    if manifest.get("profile") != profile:
        fail(f"profile mismatch: expected {profile}")
    if manifest.get("image") != image:
        fail("container image mismatch")
    if manifest.get("provenance") != {
        "type": "LOCAL_CACHE_BASELINE", "upstreamSignatureVerified": False
    }:
        fail("unsupported or overstated artifact provenance")
    if manifest.get("runtimeConfig") != runtime:
        fail("model runtime configuration mismatch")
    entries = manifest.get("models")
    if not isinstance(entries, list):
        fail("manifest models must be an array")
    roles = [item.get("role") for item in entries if isinstance(item, dict)]
    if len(roles) != len(set(roles)):
        fail("duplicate model role in manifest")
    indexed = {item.get("role"): item for item in entries if isinstance(item, dict)}
    if set(indexed) != set(models):
        fail("manifest model roles mismatch")

    for role, (model_id, revision) in models.items():
        print(f"Verifying {role} model artifact {model_id}@{revision}...", file=sys.stderr)
        entry = indexed[role]
        if entry.get("modelId") != model_id or entry.get("revision") != revision:
            fail(f"model identity mismatch for {role}")
        snapshot = snapshot_path(cache_root, model_id, revision)
        declared = entry.get("files")
        if not isinstance(declared, list) or not declared:
            fail(f"manifest files missing for {role}")
        expected_paths: set[str] = set()
        for record in declared:
            if not isinstance(record, dict):
                fail(f"invalid file record for {role}")
            relative = safe_relative_path(record.get("path"))
            relative_text = relative.as_posix()
            if relative_text in expected_paths:
                fail(f"duplicate manifest path: {relative_text}")
            expected_paths.add(relative_text)
            path = snapshot.joinpath(*relative.parts)
            if not path.is_file():
                fail(f"missing file: {role}/{relative_text}")
            actual_size = path.stat().st_size
            if record.get("size") != actual_size:
                fail(f"size mismatch: {role}/{relative_text}")
            expected_hash = record.get("sha256")
            if not isinstance(expected_hash, str) or not SHA256_PATTERN.fullmatch(expected_hash):
                fail(f"invalid checksum: {role}/{relative_text}")
            if digest(path) != expected_hash:
                fail(f"checksum mismatch: {role}/{relative_text}")
        actual_paths = {path.relative_to(snapshot).as_posix() for path in snapshot_files(snapshot, cache_root)}
        extra = sorted(actual_paths - expected_paths)
        if extra:
            fail(f"unexpected file: {role}/{extra[0]}")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    subparsers = result.add_subparsers(dest="command", required=True)
    for command in ("generate", "verify"):
        sub = subparsers.add_parser(command)
        sub.add_argument("--cache-root", required=True, type=Path)
        sub.add_argument("--expected-profile", required=True)
        sub.add_argument("--expected-image", required=True)
        sub.add_argument("--expected-runtime", action="append", default=[])
        sub.add_argument("--expected-model", action="append", default=[])
        if command == "generate":
            sub.add_argument("--output", required=True, type=Path)
        else:
            sub.add_argument("--manifest", required=True, type=Path)
    return result


def main() -> None:
    args = parser().parse_args()
    models = parse_models(args.expected_model)
    runtime = parse_runtime(args.expected_runtime)
    if args.command == "generate":
        payload = build_manifest(args.cache_root, args.expected_profile, args.expected_image, runtime, models)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, args.output)
        print(f"Model artifact manifest generated: {args.output}")
        return
    try:
        payload = json.loads(args.manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"model manifest cannot be read: {exc.__class__.__name__}")
    verify_manifest(args.cache_root, payload, args.expected_profile, args.expected_image, runtime, models)
    print("Model artifact manifest verification passed.")


if __name__ == "__main__":
    main()
