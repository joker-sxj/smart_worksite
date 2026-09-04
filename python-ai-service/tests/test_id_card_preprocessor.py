import base64
from io import BytesIO

from PIL import Image

from app.services.id_card_preprocessor import IdCardPreprocessor


def _image_data_url(width: int, height: int) -> str:
    image = Image.new("RGB", (width, height), "white")
    output = BytesIO()
    image.save(output, format="JPEG")
    return "data:image/jpeg;base64," + base64.b64encode(output.getvalue()).decode("ascii")


def test_preprocessor_keeps_original_and_bounds_enhanced_image_size():
    source = _image_data_url(2400, 1200)

    result = IdCardPreprocessor(max_dimension=1600).prepare([source])

    assert result.original_sources == [source]
    assert len(result.enhanced_sources) == 1
    payload = base64.b64decode(result.enhanced_sources[0].split(",", 1)[1])
    with Image.open(BytesIO(payload)) as enhanced:
        assert max(enhanced.size) == 1600
        assert enhanced.mode == "RGB"
    assert result.preprocessing[0]["orientationNormalized"] is True
    assert result.preprocessing[0]["enhanced"] is True


def test_preprocessor_rejects_non_image_inline_payload():
    source = "data:application/pdf;base64," + base64.b64encode(b"not-an-image").decode("ascii")

    try:
        IdCardPreprocessor().prepare([source])
    except ValueError as exc:
        assert "image data URL" in str(exc)
    else:
        raise AssertionError("non-image payload must be rejected")
