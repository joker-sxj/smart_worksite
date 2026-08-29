import base64
import binascii
import io

from pypdf import PdfReader, PdfWriter
from pypdf.errors import PdfReadError

from app.models.schemas import PdfRecoveryData, PdfRecoveryRequest


class PdfRecoveryService:
    """Safely classifies and rewrites mildly damaged PDFs in memory."""

    def recover(self, request: PdfRecoveryRequest) -> PdfRecoveryData:
        if request.maxBytes < 1 or request.maxPages < 1:
            raise ValueError("PDF recovery limits must be positive")
        try:
            content = base64.b64decode(request.contentBase64, validate=True)
        except (binascii.Error, ValueError) as ex:
            raise ValueError("PDF contentBase64 is invalid") from ex
        if len(content) > request.maxBytes:
            return PdfRecoveryData(classification="RESOURCE_LIMIT_EXCEEDED")
        if not content.lstrip().startswith(b"%PDF-"):
            return PdfRecoveryData(classification="INVALID_PDF_SIGNATURE")
        try:
            reader = PdfReader(io.BytesIO(content), strict=False)
            if reader.is_encrypted:
                return PdfRecoveryData(classification="PASSWORD_REQUIRED")
            page_count = len(reader.pages)
            if page_count > request.maxPages:
                return PdfRecoveryData(classification="PAGE_LIMIT_EXCEEDED", pageCount=page_count)
            writer = PdfWriter()
            for page in reader.pages:
                writer.add_page(page)
            output = io.BytesIO()
            writer.write(output)
            repaired = output.getvalue()
            if not repaired.startswith(b"%PDF-"):
                return PdfRecoveryData(classification="UNRECOVERABLE", pageCount=page_count)
            return PdfRecoveryData(
                classification="RECOVERED",
                recoverable=True,
                repairedContentBase64=base64.b64encode(repaired).decode("ascii"),
                pageCount=page_count,
                warnings=["source PDF was rewritten with pypdf strict mode disabled"],
            )
        except (PdfReadError, OSError, ValueError, KeyError, TypeError):
            return PdfRecoveryData(classification="UNRECOVERABLE")
