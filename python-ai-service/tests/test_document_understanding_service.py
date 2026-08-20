import asyncio

import pytest

from app.models.schemas import DocumentUnderstandingPageInput, DocumentUnderstandingRequest
from app.services.document_understanding_service import DocumentUnderstandingService


class FakeVisionProvider:
    def __init__(self, texts: list[str]):
        self.texts = iter(texts)
        self.calls: list[tuple[str, str | list[str], str | None]] = []

    async def vision_json_chat(self, prompt, file_sources, content_type=None):
        self.calls.append((prompt, file_sources, content_type))
        return {"text": next(self.texts)}, {"completion_tokens": 3}


def run(service, request):
    return asyncio.run(service.understand(request))


def test_native_text_pages_preserve_page_location_without_ocr():
    provider = FakeVisionProvider([])
    data, usage = run(DocumentUnderstandingService(provider), DocumentUnderstandingRequest(
        pages=[
            DocumentUnderstandingPageInput(pageNo=1, nativeText="Page one native safety rules"),
            DocumentUnderstandingPageInput(pageNo=2, nativeText="Page two native inspection records"),
        ],
        minNativeTextChars=10,
        maxPages=5,
        maxTextChars=1000,
    ))

    assert [(page.pageNo, page.source, page.text) for page in data.pages] == [
        (1, "NATIVE", "Page one native safety rules"),
        (2, "NATIVE", "Page two native inspection records"),
    ]
    assert provider.calls == []
    assert usage == {}


def test_scanned_page_uses_local_vision_ocr_and_preserves_page_number():
    provider = FakeVisionProvider(["Scanned permit text"])
    data, usage = run(DocumentUnderstandingService(provider), DocumentUnderstandingRequest(
        pages=[DocumentUnderstandingPageInput(
            pageNo=4,
            nativeText="",
            imageDataUrl="data:image/png;base64,AA==",
        )],
        minNativeTextChars=10,
        maxPages=5,
        maxTextChars=1000,
    ))

    assert [(page.pageNo, page.source, page.text) for page in data.pages] == [
        (4, "OCR", "Scanned permit text"),
    ]
    assert len(provider.calls) == 1
    assert usage == {"completion_tokens": 3}


def test_mixed_pdf_uses_native_and_ocr_per_page():
    provider = FakeVisionProvider(["OCR page two"])
    data, _ = run(DocumentUnderstandingService(provider), DocumentUnderstandingRequest(
        pages=[
            DocumentUnderstandingPageInput(pageNo=1, nativeText="Native page one content"),
            DocumentUnderstandingPageInput(pageNo=2, nativeText="x", imageDataUrl="data:image/png;base64,AA=="),
            DocumentUnderstandingPageInput(pageNo=3, nativeText="Native page three content"),
        ],
        minNativeTextChars=10,
        maxPages=3,
        maxTextChars=1000,
    ))

    assert [(page.pageNo, page.source) for page in data.pages] == [
        (1, "NATIVE"), (2, "OCR"), (3, "NATIVE")
    ]


def test_page_and_text_budgets_are_enforced():
    provider = FakeVisionProvider([])
    service = DocumentUnderstandingService(provider)
    with pytest.raises(ValueError, match="page count exceeds"):
        run(service, DocumentUnderstandingRequest(
            pages=[
                DocumentUnderstandingPageInput(pageNo=1, nativeText="first page"),
                DocumentUnderstandingPageInput(pageNo=2, nativeText="second page"),
            ],
            minNativeTextChars=1,
            maxPages=1,
            maxTextChars=100,
        ))

    data, _ = run(service, DocumentUnderstandingRequest(
        pages=[
            DocumentUnderstandingPageInput(pageNo=1, nativeText="12345678"),
            DocumentUnderstandingPageInput(pageNo=2, nativeText="abcdefgh"),
        ],
        minNativeTextChars=1,
        maxPages=2,
        maxTextChars=10,
    ))
    assert data.truncated is True
    assert data.totalTextChars == 10
    assert [(page.pageNo, page.text, page.truncated) for page in data.pages] == [
        (1, "12345678", False),
        (2, "ab", True),
    ]


def test_does_not_call_ocr_after_text_budget_is_exhausted():
    provider = FakeVisionProvider([])
    data, _ = run(DocumentUnderstandingService(provider), DocumentUnderstandingRequest(
        pages=[
            DocumentUnderstandingPageInput(pageNo=1, nativeText="12345678"),
            DocumentUnderstandingPageInput(
                pageNo=2,
                nativeText="",
                imageDataUrl="data:image/png;base64,AA==",
            ),
        ],
        minNativeTextChars=1,
        maxPages=2,
        maxTextChars=8,
    ))

    assert data.truncated is True
    assert provider.calls == []
    assert [(page.pageNo, page.text) for page in data.pages] == [(1, "12345678")]


def test_validates_duplicate_page_numbers_before_budget_short_circuit():
    provider = FakeVisionProvider([])
    with pytest.raises(ValueError, match="page numbers"):
        run(DocumentUnderstandingService(provider), DocumentUnderstandingRequest(
            pages=[
                DocumentUnderstandingPageInput(pageNo=1, nativeText="12345678"),
                DocumentUnderstandingPageInput(pageNo=1, nativeText="ignored"),
            ],
            minNativeTextChars=1,
            maxPages=2,
            maxTextChars=8,
        ))
    assert provider.calls == []
