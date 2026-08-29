import base64
import io

from pypdf import PdfReader, PdfWriter

from app.models.schemas import PdfRecoveryRequest
from app.services.pdf_recovery_service import PdfRecoveryService


def test_rejects_non_pdf_signature():
    result = PdfRecoveryService().recover(PdfRecoveryRequest(contentBase64=base64.b64encode(b"nope").decode()))
    assert result.classification == "INVALID_PDF_SIGNATURE"


def test_enforces_byte_limit():
    result = PdfRecoveryService().recover(PdfRecoveryRequest(
        contentBase64=base64.b64encode(b"%PDF-1.7").decode(), maxBytes=2))
    assert result.classification == "RESOURCE_LIMIT_EXCEEDED"


def test_rewrites_valid_pdf_and_preserves_page_count():
    writer = PdfWriter()
    writer.add_blank_page(width=595, height=842)
    source = io.BytesIO()
    writer.write(source)
    result = PdfRecoveryService().recover(PdfRecoveryRequest(
        contentBase64=base64.b64encode(source.getvalue()).decode()))
    assert result.classification == "RECOVERED"
    assert result.pageCount == 1
    repaired = base64.b64decode(result.repairedContentBase64)
    assert len(PdfReader(io.BytesIO(repaired)).pages) == 1


def test_classifies_encrypted_pdf_without_attempting_password_recovery():
    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    writer.encrypt("secret")
    source = io.BytesIO()
    writer.write(source)
    result = PdfRecoveryService().recover(PdfRecoveryRequest(
        contentBase64=base64.b64encode(source.getvalue()).decode()))
    assert result.classification == "PASSWORD_REQUIRED"
    assert result.repairedContentBase64 is None
