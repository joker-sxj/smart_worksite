from typing import Any

from app.models.schemas import (
    DocumentUnderstandingData,
    DocumentUnderstandingPageData,
    DocumentUnderstandingRequest,
)


class DocumentUnderstandingService:
    """Extract page text, using the configured local vision provider only for scanned pages."""

    def __init__(self, vision_provider):
        self.vision_provider = vision_provider

    async def understand(self, request: DocumentUnderstandingRequest) -> tuple[DocumentUnderstandingData, dict[str, Any]]:
        if len(request.pages) > request.maxPages:
            raise ValueError("page count exceeds document understanding limit")
        if request.maxTextChars < 1:
            raise ValueError("maxTextChars must be positive")
        if request.minNativeTextChars < 0:
            raise ValueError("minNativeTextChars must not be negative")

        page_numbers = [page.pageNo for page in request.pages]
        if any(page_no < 1 for page_no in page_numbers) or len(set(page_numbers)) != len(page_numbers):
            raise ValueError("page numbers must be positive and unique")

        results: list[DocumentUnderstandingPageData] = []
        usage: dict[str, Any] = {}
        remaining = request.maxTextChars
        for page in request.pages:
            if remaining == 0:
                break
            native = (page.nativeText or "").strip()
            if len(native) >= request.minNativeTextChars:
                text, source = native, "NATIVE"
            else:
                if not page.imageDataUrl:
                    raise ValueError(f"page {page.pageNo} has no usable native text or OCR image")
                payload, page_usage = await self.vision_provider.vision_json_chat(
                    'Extract only text visibly present on this PDF page in reading order. Return JSON: {"text":"..."}.',
                    page.imageDataUrl,
                    "image/png",
                )
                text = str(payload.get("text") or "").strip()
                source = "OCR"
                for key, value in (page_usage or {}).items():
                    if isinstance(value, (int, float)) and isinstance(usage.get(key), (int, float)):
                        usage[key] += value
                    elif key not in usage:
                        usage[key] = value
            clipped = text[:remaining]
            was_truncated = len(clipped) < len(text)
            results.append(DocumentUnderstandingPageData(
                pageNo=page.pageNo,
                source=source,
                text=clipped,
                truncated=was_truncated,
            ))
            remaining -= len(clipped)
        combined = "\n\n".join(page.text for page in results if page.text)
        return DocumentUnderstandingData(
            text=combined,
            totalTextChars=sum(len(page.text) for page in results),
            truncated=(len(results) < len(request.pages) or any(page.truncated for page in results)),
            pages=results,
        ), usage
