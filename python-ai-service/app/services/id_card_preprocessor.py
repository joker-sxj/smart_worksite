import base64
from dataclasses import dataclass
from io import BytesIO
from typing import Any

from PIL import Image, ImageEnhance, ImageOps


@dataclass(frozen=True)
class PreparedIdCardImages:
    original_sources: list[str]
    enhanced_sources: list[str]
    preprocessing: list[dict[str, Any]]


class IdCardPreprocessor:
    def __init__(self, max_dimension: int = 2048):
        if max_dimension < 512:
            raise ValueError("max_dimension must be at least 512")
        self.max_dimension = max_dimension

    def prepare(self, sources: list[str]) -> PreparedIdCardImages:
        enhanced: list[str] = []
        metadata: list[dict[str, Any]] = []
        for source in sources:
            if not source.startswith("data:image/"):
                raise ValueError("id card preprocessing requires image data URL")
            try:
                encoded = source.split(",", 1)[1]
                with Image.open(BytesIO(base64.b64decode(encoded))) as image:
                    normalized = ImageOps.exif_transpose(image).convert("RGB")
                    normalized.thumbnail((self.max_dimension, self.max_dimension), Image.Resampling.LANCZOS)
                    improved = ImageEnhance.Contrast(normalized).enhance(1.15)
                    improved = ImageEnhance.Sharpness(improved).enhance(1.2)
                    output = BytesIO()
                    improved.save(output, format="JPEG", quality=92, optimize=True)
                    enhanced.append("data:image/jpeg;base64," + base64.b64encode(output.getvalue()).decode("ascii"))
                    metadata.append({
                        "orientationNormalized": True,
                        "enhanced": True,
                        "width": improved.width,
                        "height": improved.height,
                    })
            except Exception as exc:
                raise ValueError("id card image preprocessing failed") from exc
        return PreparedIdCardImages(sources, enhanced, metadata)
