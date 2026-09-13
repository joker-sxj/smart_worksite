"""Local character OCR providers used before Qwen semantic extraction.

The provider boundary keeps PP-OCRv5 optional and offline.  A configured
PP-OCRv5 provider fails closed when its local runtime is unavailable; AUTO is
the only compatibility mode that may retain the existing Qwen-only path.
"""

import base64
import os
from dataclasses import dataclass
from functools import lru_cache
from io import BytesIO
from typing import Any, Callable

from PIL import Image


@dataclass(frozen=True)
class OcrTextLine:
    text: str
    confidence: float
    box: list[int] | None = None


@dataclass(frozen=True)
class OcrTextResult:
    provider: str
    model: str
    text: str
    lines: list[OcrTextLine]


class QwenOnlyOcrProvider:
    provider_name = "QWEN_VL"

    def recognize(self, sources: list[str]) -> OcrTextResult:
        return OcrTextResult(self.provider_name, "semantic-only", "", [])


class PaddleOcrV5Provider:
    provider_name = "PADDLE_OCRV5"

    def __init__(self, detection_model_dir: str | None = None,
                 recognition_model_dir: str | None = None, device: str | None = None,
                 ocr_factory: Callable[[], Any] | None = None):
        self.detection_model_dir = detection_model_dir or os.getenv("OCR_PADDLE_DET_MODEL_DIR", "")
        self.recognition_model_dir = recognition_model_dir or os.getenv("OCR_PADDLE_REC_MODEL_DIR", "")
        self.device = device or os.getenv("OCR_PADDLE_DEVICE", "cpu")
        self._ocr_factory = ocr_factory
        self._ocr: Any | None = None

    @staticmethod
    def available() -> bool:
        try:
            import paddle  # noqa: F401
            import paddleocr  # noqa: F401
            return True
        except ImportError:
            return False

    @property
    def model(self) -> str:
        return "PP-OCRv5"

    def _engine(self) -> Any:
        if self._ocr is not None:
            return self._ocr
        if self._ocr_factory is not None:
            self._ocr = self._ocr_factory(**self._engine_kwargs())
            return self._ocr
        if not self.available():
            raise RuntimeError("PaddleOCR/PaddlePaddle is not installed for PP-OCRv5")
        from paddleocr import PaddleOCR
        if not self.detection_model_dir or not self.recognition_model_dir:
            raise RuntimeError("PP-OCRv5 local detection and recognition model directories are required")
        for path in (self.detection_model_dir, self.recognition_model_dir):
            if not os.path.isdir(path):
                raise RuntimeError(f"PP-OCRv5 local model directory is not available: {path}")
        self._ocr = PaddleOCR(**self._engine_kwargs())
        return self._ocr

    def _engine_kwargs(self) -> dict[str, Any]:
        return {
            "text_detection_model_name": "PP-OCRv5_server_det",
            "text_recognition_model_name": "PP-OCRv5_server_rec",
            "use_doc_orientation_classify": False,
            "use_doc_unwarping": False,
            "use_textline_orientation": False,
            # PaddlePaddle 3.3.1 oneDNN cannot execute the pinned PP-OCRv5
            # model's PIR array attribute on CPU; the plain CPU executor can.
            "enable_mkldnn": False,
            "device": self.device,
            "text_detection_model_dir": self.detection_model_dir,
            "text_recognition_model_dir": self.recognition_model_dir,
        }

    def recognize(self, sources: list[str]) -> OcrTextResult:
        lines: list[OcrTextLine] = []
        engine = self._engine()
        for source in sources:
            image = self._decode_image(source)
            for result in engine.predict(self._predict_input(image)):
                payload = result.json if hasattr(result, "json") else result
                if callable(payload):
                    payload = payload()
                if isinstance(payload, dict) and "res" in payload:
                    payload = payload["res"]
                if not isinstance(payload, dict):
                    continue
                texts = payload.get("rec_texts") or []
                scores = payload.get("rec_scores") or []
                boxes = payload.get("rec_boxes") or []
                for index, value in enumerate(texts):
                    text = str(value).strip()
                    if not text:
                        continue
                    confidence = self._confidence(scores[index] if index < len(scores) else 0)
                    box = self._box(boxes[index]) if index < len(boxes) else None
                    lines.append(OcrTextLine(text, confidence, box))
        return OcrTextResult(self.provider_name, self.model, "\n".join(line.text for line in lines), lines)

    @staticmethod
    def _predict_input(image: Image.Image) -> Any:
        import numpy as np

        return np.asarray(image)

    def _decode_image(self, source: str) -> Image.Image:
        if not source.startswith("data:image/"):
            raise ValueError("PP-OCRv5 provider requires local data image sources")
        try:
            encoded = source.split(",", 1)[1]
            return Image.open(BytesIO(base64.b64decode(encoded))).convert("RGB")
        except (IndexError, ValueError, base64.binascii.Error) as exc:
            raise ValueError("invalid local OCR image data") from exc

    def _confidence(self, value: Any) -> float:
        try:
            return max(0.0, min(1.0, float(value)))
        except (TypeError, ValueError):
            return 0.0

    def _box(self, value: Any) -> list[int] | None:
        if not isinstance(value, (list, tuple)) or len(value) != 4:
            return None
        try:
            return [int(item) for item in value]
        except (TypeError, ValueError):
            return None


def build_ocr_provider() -> PaddleOcrV5Provider | QwenOnlyOcrProvider:
    mode = os.getenv("OCR_PROVIDER", "QWEN").strip().upper()
    return _build_ocr_provider_cached(
        mode,
        os.getenv("OCR_PADDLE_DET_MODEL_DIR", ""),
        os.getenv("OCR_PADDLE_REC_MODEL_DIR", ""),
        os.getenv("OCR_PADDLE_DEVICE", "cpu"),
    )


@lru_cache(maxsize=4)
def _build_ocr_provider_cached(mode: str, det_dir: str, rec_dir: str, device: str) \
        -> PaddleOcrV5Provider | QwenOnlyOcrProvider:
    if mode in {"PP_OCRV5", "PADDLE", "PADDLEOCR"}:
        provider = PaddleOcrV5Provider(det_dir, rec_dir, device)
        if not provider.available():
            raise RuntimeError("PaddleOCR/PaddlePaddle is not installed for PP-OCRv5")
        if not provider.detection_model_dir or not provider.recognition_model_dir:
            raise RuntimeError("PP-OCRv5 local detection and recognition model directories are required")
        if not all(os.path.isdir(path) for path in (provider.detection_model_dir, provider.recognition_model_dir)):
            raise RuntimeError("PP-OCRv5 local model directories are not available")
        return provider
    candidate = PaddleOcrV5Provider(det_dir, rec_dir, device)
    if mode == "AUTO" and candidate.available() and candidate.detection_model_dir and candidate.recognition_model_dir \
            and os.path.isdir(candidate.detection_model_dir) and os.path.isdir(candidate.recognition_model_dir):
        return candidate
    return QwenOnlyOcrProvider()


def clear_ocr_provider_cache() -> None:
    _build_ocr_provider_cached.cache_clear()


def ocr_provider_status() -> dict[str, Any]:
    mode = os.getenv("OCR_PROVIDER", "QWEN").strip().upper()
    paddle_selected = mode in {"PP_OCRV5", "PADDLE", "PADDLEOCR"}
    dependency_ready = PaddleOcrV5Provider.available()
    det_dir = os.getenv("OCR_PADDLE_DET_MODEL_DIR", "")
    rec_dir = os.getenv("OCR_PADDLE_REC_MODEL_DIR", "")
    models_ready = bool(det_dir and rec_dir and os.path.isdir(det_dir) and os.path.isdir(rec_dir))
    return {
        "configuredProvider": "PADDLE_OCRV5" if paddle_selected else mode,
        "activeProvider": "PADDLE_OCRV5" if paddle_selected and dependency_ready and models_ready else "QWEN_VL",
        "model": "PP-OCRv5" if paddle_selected else "Qwen vision semantic OCR",
        "dependencyReady": dependency_ready,
        "modelsReady": models_ready if paddle_selected else True,
        "device": os.getenv("OCR_PADDLE_DEVICE", "cpu") if paddle_selected else "model-service",
        "status": "READY" if not paddle_selected or (dependency_ready and models_ready) else "NOT_READY",
    }
