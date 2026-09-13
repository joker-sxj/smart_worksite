import base64
from io import BytesIO

from PIL import Image
import numpy as np

from app.services.ocr_provider import (
    OcrTextResult,
    PaddleOcrV5Provider,
    build_ocr_provider,
    ocr_provider_status,
)


def _image_data_url() -> str:
    output = BytesIO()
    Image.new("RGB", (8, 8), "white").save(output, format="PNG")
    return "data:image/png;base64," + base64.b64encode(output.getvalue()).decode("ascii")


def test_paddle_provider_normalizes_predict_result_without_exposing_binary_content():
    class FakePaddle:
        def predict(self, source):
            assert isinstance(source, np.ndarray)
            assert source.shape == (8, 8, 3)
            return [{
                "rec_texts": ["项目名称", "青特赫府"],
                "rec_scores": [0.98, 0.91],
                "rec_boxes": [[1, 2, 30, 12], [1, 15, 50, 25]],
            }]

    result = PaddleOcrV5Provider(ocr_factory=lambda **_: FakePaddle()).recognize([_image_data_url()])

    assert isinstance(result, OcrTextResult)
    assert result.provider == "PADDLE_OCRV5"
    assert result.text == "项目名称\n青特赫府"
    assert result.lines[0].text == "项目名称"
    assert result.lines[0].confidence == 0.98
    assert result.lines[0].box == [1, 2, 30, 12]
    assert "base64" not in repr(result)


def test_configured_paddle_provider_fails_closed_when_dependency_is_unavailable(monkeypatch):
    monkeypatch.setenv("OCR_PROVIDER", "PP_OCRV5")
    monkeypatch.setattr("app.services.ocr_provider.PaddleOcrV5Provider.available", staticmethod(lambda: False))

    try:
        build_ocr_provider()
        assert False, "configured PP-OCRv5 must not silently fall back"
    except RuntimeError as exc:
        assert "PaddleOCR" in str(exc)


def test_auto_provider_preserves_qwen_compatibility_when_paddle_is_not_installed(monkeypatch):
    monkeypatch.setenv("OCR_PROVIDER", "AUTO")
    monkeypatch.setattr("app.services.ocr_provider.PaddleOcrV5Provider.available", staticmethod(lambda: False))

    provider = build_ocr_provider()

    assert provider.provider_name == "QWEN_VL"


def test_paddle_readiness_requires_both_offline_model_directories(monkeypatch, tmp_path):
    monkeypatch.setenv("OCR_PROVIDER", "PP_OCRV5")
    monkeypatch.setenv("OCR_PADDLE_DET_MODEL_DIR", str(tmp_path / "det"))
    monkeypatch.setenv("OCR_PADDLE_REC_MODEL_DIR", str(tmp_path / "rec"))
    monkeypatch.setattr("app.services.ocr_provider.PaddleOcrV5Provider.available", staticmethod(lambda: True))

    status = ocr_provider_status()

    assert status["status"] == "NOT_READY"
    assert status["activeProvider"] == "QWEN_VL"


def test_paddle_provider_selects_pp_ocrv5_model_names_for_local_directories(tmp_path):
    captured = {}

    class FakePaddle:
        def __init__(self, **kwargs):
            captured.update(kwargs)

    det = tmp_path / "det"
    rec = tmp_path / "rec"
    det.mkdir()
    rec.mkdir()
    provider = PaddleOcrV5Provider(str(det), str(rec), ocr_factory=FakePaddle)

    provider._engine()

    assert captured["text_detection_model_dir"] == str(det)
    assert captured["text_recognition_model_dir"] == str(rec)
    assert captured["text_detection_model_name"] == "PP-OCRv5_server_det"
    assert captured["text_recognition_model_name"] == "PP-OCRv5_server_rec"
