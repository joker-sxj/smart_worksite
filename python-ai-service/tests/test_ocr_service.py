import asyncio
import base64
from io import BytesIO

from PIL import Image

from app.models.schemas import OcrFilePayload, OcrRecognizeRequest
from app.services.ocr_service import OcrService


def _image_url() -> str:
    output = BytesIO()
    Image.new("RGB", (800, 500), "white").save(output, format="JPEG")
    return "data:image/jpeg;base64," + base64.b64encode(output.getvalue()).decode("ascii")


def test_id_card_dual_pass_marks_conflicting_field_for_manual_confirmation():
    class FakeQwen:
        def __init__(self):
            self.calls = 0

        async def vision_json_chat(self, prompt, file_sources, content_type):
            self.calls += 1
            value = "张三" if self.calls == 1 else "张玉"
            return {
                "ocrType": "ID_CARD",
                "confidence": 0.9,
                "fields": [{
                    "fieldKey": "name", "fieldName": "姓名",
                    "fieldValue": value, "confidence": 0.9,
                }],
                "extras": {},
            }, {"completion_tokens": 10}

    qwen = FakeQwen()
    request = OcrRecognizeRequest(
        projectId=1,
        recordId=1,
        ocrType="ID_CARD",
        file=OcrFilePayload(
            fileId=1,
            fileName="id-card.jpg",
            contentType="image/jpeg",
            dataUrls=[_image_url()],
        ),
    )

    data, usage = asyncio.run(OcrService(qwen).recognize(request))

    assert qwen.calls == 2
    assert data.fields[0].fieldValue == ""
    assert data.fields[0].manualConfirmationRequired is True
    assert data.extras["dualPass"]["conflicts"] == ["name"]
    assert usage["ocrPasses"] == 2


def _recognize(ocr_type: str, raw: dict, *, options: dict | None = None):
    class FakeQwen:
        async def vision_json_chat(self, prompt, file_sources, content_type):
            return raw, {"completion_tokens": 10}

    request = OcrRecognizeRequest(
        projectId=1,
        recordId=1,
        ocrType=ocr_type,
        options=options or {},
        file=OcrFilePayload(
            fileId=1,
            fileName="fixture.jpg",
            contentType="image/jpeg",
            dataUrls=[_image_url()],
        ),
    )
    return asyncio.run(OcrService(FakeQwen()).recognize(request))[0]


def test_license_plate_normalizes_separator_and_accepts_new_energy_number():
    raw = {
        "ocrType": "LICENSE_PLATE",
        "confidence": 0.95,
        "fields": [
            {"fieldKey": "plateNumber", "fieldName": "车牌号", "fieldValue": "粤 B·D12345", "confidence": 0.95},
            {"fieldKey": "backgroundColor", "fieldName": "底色", "fieldValue": "绿色", "confidence": 0.92},
            {"fieldKey": "fontColor", "fieldName": "字号颜色", "fieldValue": "黑色", "confidence": 0.92},
            {"fieldKey": "plateType", "fieldName": "车牌类型", "fieldValue": "小型新能源汽车", "confidence": 0.9},
        ],
        "extras": {},
    }

    data = _recognize("LICENSE_PLATE", raw)

    assert data.fields[0].fieldValue == "粤BD12345"
    assert data.fields[0].manualConfirmationRequired is False
    assert data.extras["validation"]["plateNumberValid"] is True


def test_license_plate_marks_structurally_invalid_number_for_confirmation():
    raw = {
        "ocrType": "LICENSE_PLATE",
        "confidence": 0.9,
        "fields": [{"fieldKey": "plateNumber", "fieldName": "车牌号", "fieldValue": "ABC123", "confidence": 0.99}],
        "extras": {},
    }

    data = _recognize("LICENSE_PLATE", raw)

    assert data.fields[0].manualConfirmationRequired is True
    assert data.extras["validation"]["plateNumberValid"] is False


def test_invoice_validates_amount_equation_with_decimal_currency_values():
    raw = {
        "ocrType": "INVOICE",
        "confidence": 0.95,
        "fields": [
            {"fieldKey": "invoiceType", "fieldName": "发票类型", "fieldValue": "增值税专用发票", "confidence": 0.95},
            {"fieldKey": "amountWithoutTax", "fieldName": "不含税金额", "fieldValue": "¥1,000.00", "confidence": 0.95},
            {"fieldKey": "taxAmount", "fieldName": "税额", "fieldValue": "130.00", "confidence": 0.95},
            {"fieldKey": "totalAmount", "fieldName": "价税合计", "fieldValue": "1,130.00元", "confidence": 0.95},
        ],
        "extras": {},
    }

    data = _recognize("INVOICE", raw, options={"invoiceType": "VAT_SPECIAL"})

    assert data.extras["validation"]["amountsConsistent"] is True
    assert data.extras["validation"]["invoiceTypeConsistent"] is True


def test_invoice_marks_amounts_and_type_for_confirmation_when_checks_conflict():
    raw = {
        "ocrType": "INVOICE",
        "confidence": 0.95,
        "fields": [
            {"fieldKey": "invoiceType", "fieldName": "发票类型", "fieldValue": "增值税普通发票", "confidence": 0.95},
            {"fieldKey": "amountWithoutTax", "fieldName": "不含税金额", "fieldValue": "100.00", "confidence": 0.95},
            {"fieldKey": "taxAmount", "fieldName": "税额", "fieldValue": "13.00", "confidence": 0.95},
            {"fieldKey": "totalAmount", "fieldName": "价税合计", "fieldValue": "999.00", "confidence": 0.95},
        ],
        "extras": {},
    }

    data = _recognize("INVOICE", raw, options={"invoiceType": "VAT_SPECIAL"})
    fields = {field.fieldKey: field for field in data.fields}

    assert data.extras["validation"]["amountsConsistent"] is False
    assert data.extras["validation"]["invoiceTypeConsistent"] is False
    assert fields["invoiceType"].manualConfirmationRequired is True
    assert fields["amountWithoutTax"].manualConfirmationRequired is True
    assert fields["taxAmount"].manualConfirmationRequired is True
    assert fields["totalAmount"].manualConfirmationRequired is True
