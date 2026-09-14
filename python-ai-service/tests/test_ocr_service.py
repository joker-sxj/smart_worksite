import asyncio
import base64
from io import BytesIO

from PIL import Image

from app.models.schemas import OcrFieldData, OcrFilePayload, OcrRecognizeData, OcrRecognizeRequest
from app.services.ocr_service import OcrService
import pytest


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
    assert data.fields[0].confirmationReason == "DUAL_PASS_CONFLICT"
    assert [candidate.model_dump() for candidate in data.fields[0].candidates] == [
        {"value": "张三", "confidence": 0.9, "evidence": None, "source": "ORIGINAL"},
        {"value": "张玉", "confidence": 0.9, "evidence": None, "source": "ENHANCED"},
    ]
    assert data.extras["dualPass"]["conflicts"] == ["name"]
    assert usage["ocrPasses"] == 2


def test_document_type_detection_requires_strong_indicator_and_flags_mismatch():
    service = OcrService(None)
    data = OcrRecognizeData(
        ocrType="PASSPORT", fields=[OcrFieldData(fieldKey="passportNumber", fieldName="护照号码")],
        extras={"visualIndicators": ["中华人民共和国外国人永久居留身份证"]},
    )

    result = service._apply_document_diagnostics(data, "PASSPORT")

    assert result.extras["documentType"] == {
        "selectedType": "PASSPORT", "detectedType": "FIVE_STAR_CARD", "confidence": 0.98, "mismatch": True,
    }
    assert result.fields[0].confirmationReason == "TYPE_MISMATCH"
    assert result.fields[0].manualConfirmationRequired is True


def test_document_type_detection_degrades_unverified_model_guess_to_unknown():
    service = OcrService(None)
    data = OcrRecognizeData(
        ocrType="PASSPORT", fields=[OcrFieldData(fieldKey="passportNumber", fieldName="护照号码")],
        extras={"documentType": {"detectedType": "FIVE_STAR_CARD", "confidence": 0.99}},
    )

    result = service._apply_document_diagnostics(data, "PASSPORT")

    assert result.extras["documentType"]["detectedType"] == "UNKNOWN"
    assert result.extras["documentType"]["mismatch"] is False


def test_id_front_only_and_masked_source_receive_distinct_reasons():
    service = OcrService(None)
    data = OcrRecognizeData(
        ocrType="ID_CARD",
        fields=[
            OcrFieldData(fieldKey="name", fieldName="姓名", fieldValue="**", confidence=0.1, manualConfirmationRequired=True),
            OcrFieldData(fieldKey="issuingAuthority", fieldName="签发机关", manualConfirmationRequired=True),
            OcrFieldData(fieldKey="validPeriod", fieldName="有效期限", manualConfirmationRequired=True),
        ],
        extras={"sideDetected": ["FRONT"], "maskedFields": ["name"]},
    )

    result = service._apply_document_diagnostics(data, "ID_CARD")

    fields = {field.fieldKey: field for field in result.fields}
    assert fields["name"].confirmationReason == "SOURCE_MASKED"
    assert fields["issuingAuthority"].confirmationReason == "MISSING_SIDE_OR_PAGE"
    assert fields["validPeriod"].confirmationReason == "MISSING_SIDE_OR_PAGE"


def test_contract_fragment_does_not_claim_missing_document_fields_failed_ocr():
    service = OcrService(None)
    data = OcrRecognizeData(
        ocrType="CONTRACT",
        fields=[OcrFieldData(fieldKey="contractNumber", fieldName="合同编号", manualConfirmationRequired=True)],
        extras={"visibleScope": "CONTRACT_FRAGMENT"},
    )

    result = service._apply_document_diagnostics(data, "CONTRACT")

    assert result.fields[0].confirmationReason == "FIELD_NOT_VISIBLE"


def _dual_pass_field(
    field_key: str,
    left_value: str,
    right_value: str,
    *,
    ocr_type: str = "ID_CARD",
    left_confidence: float = 0.8,
    right_confidence: float = 0.9,
):
    service = OcrService(None)
    field_name = field_key
    original = OcrRecognizeData(
        ocrType=ocr_type,
        fields=[OcrFieldData(
            fieldKey=field_key,
            fieldName=field_name,
            fieldValue=left_value,
            confidence=left_confidence,
            recognized=True,
            evidence="original evidence",
        )],
    )
    enhanced = OcrRecognizeData(
        ocrType=ocr_type,
        fields=[OcrFieldData(
            fieldKey=field_key,
            fieldName=field_name,
            fieldValue=right_value,
            confidence=right_confidence,
            recognized=True,
            evidence="enhanced evidence",
        )],
    )
    return service._merge_dual_pass(original, enhanced).fields[0]


def test_dual_pass_treats_nfkc_spacing_punctuation_and_case_as_equivalent():
    field = _dual_pass_field("buyerName", "ＡＣＭＥ， 公司", "acme公司", ocr_type="INVOICE")

    assert field.fieldValue == "acme公司"
    assert field.manualConfirmationRequired is False
    assert field.confirmationReason is None


def test_dual_pass_treats_variable_width_valid_dates_as_equivalent():
    field = _dual_pass_field("birthDate", "1992年1月18日", "1992-01-18")

    assert field.fieldValue == "1992-01-18"
    assert field.manualConfirmationRequired is False


def test_dual_pass_treats_formatted_document_numbers_as_equivalent():
    field = _dual_pass_field("passportNumber", "ｅ 12-34 567", "E1234567", ocr_type="PASSPORT")

    assert field.fieldValue == "E1234567"
    assert field.manualConfirmationRequired is False


def test_dual_pass_does_not_erase_decimal_semantics_from_amounts():
    field = _dual_pass_field("totalAmount", "1.00", "100", ocr_type="INVOICE")

    assert field.fieldValue == ""
    assert field.manualConfirmationRequired is True
    assert field.confirmationReason == "DUAL_PASS_CONFLICT"


def test_dual_pass_selects_the_only_parseable_amount_candidate():
    field = _dual_pass_field("totalAmount", "¥1,130.00", "not-an-amount", ocr_type="INVOICE")

    assert field.fieldValue == "¥1,130.00"
    assert field.manualConfirmationRequired is False
    assert field.confirmationReason is None
    assert field.candidates == []


def test_dual_pass_aligns_fields_by_key_when_provider_order_differs():
    service = OcrService(None)
    original = OcrRecognizeData(ocrType="ID_CARD", fields=[
        OcrFieldData(fieldKey="name", fieldName="姓名", fieldValue="张三", confidence=0.9),
        OcrFieldData(fieldKey="gender", fieldName="性别", fieldValue="男", confidence=0.8),
    ])
    enhanced = OcrRecognizeData(ocrType="ID_CARD", fields=[
        OcrFieldData(fieldKey="gender", fieldName="性别", fieldValue="男", confidence=0.9),
        OcrFieldData(fieldKey="name", fieldName="姓名", fieldValue="张三", confidence=0.8),
    ])

    fields = service._merge_dual_pass(original, enhanced).fields

    assert [(field.fieldKey, field.fieldValue) for field in fields] == [("name", "张三"), ("gender", "男")]
    assert all(not field.manualConfirmationRequired for field in fields)


def test_dual_pass_preserves_single_visible_text_without_empty_conflict_candidate():
    field = _dual_pass_field("name", "张三", "")

    assert field.fieldValue == "张三"
    assert field.recognized is True
    assert field.manualConfirmationRequired is True
    assert field.confirmationReason == "DUAL_PASS_CONFLICT"
    assert field.candidates == []


def test_dual_pass_equivalent_low_confidence_values_still_require_confirmation():
    field = _dual_pass_field(
        "name",
        "ＡＣＭＥ 公司",
        "acme公司",
        left_confidence=0.3,
        right_confidence=0.4,
    )

    assert field.fieldValue == "acme公司"
    assert field.manualConfirmationRequired is True
    assert field.confirmationReason == "LOW_CONFIDENCE"


def test_dual_pass_selects_the_only_deterministically_valid_candidate():
    field = _dual_pass_field(
        "idNumber",
        "11010519491231002X",
        "110105194912310021",
        right_confidence=0.99,
    )

    assert field.fieldValue == "11010519491231002X"
    assert field.confidence == 0.8
    assert field.manualConfirmationRequired is False
    assert field.candidates == []


def test_dual_pass_preserves_two_distinct_valid_candidates_for_confirmation():
    field = _dual_pass_field(
        "idNumber",
        "11010519491231002X",
        "510302199201182323",
    )

    assert field.fieldValue == ""
    assert field.manualConfirmationRequired is True
    assert field.confirmationReason == "DUAL_PASS_CONFLICT"
    assert [candidate.model_dump() for candidate in field.candidates] == [
        {
            "value": "11010519491231002X",
            "confidence": 0.8,
            "evidence": "original evidence",
            "source": "ORIGINAL",
        },
        {
            "value": "510302199201182323",
            "confidence": 0.9,
            "evidence": "enhanced evidence",
            "source": "ENHANCED",
        },
    ]


def test_id_card_validates_checksum_and_birth_date_consistency():
    raw = {
        "ocrType": "ID_CARD",
        "confidence": 0.96,
        "fields": [
            {"fieldKey": "birthDate", "fieldName": "出生日期", "fieldValue": "1949年12月31日", "confidence": 0.96},
            {"fieldKey": "idNumber", "fieldName": "身份证号", "fieldValue": "110105 19491231 002x", "confidence": 0.96},
        ],
        "extras": {},
    }

    data = _recognize("ID_CARD", raw)
    fields = {field.fieldKey: field for field in data.fields}

    assert fields["idNumber"].fieldValue == "11010519491231002X"
    assert fields["idNumber"].manualConfirmationRequired is False
    assert data.extras["validation"]["idNumberValid"] is True
    assert data.extras["validation"]["birthDateConsistent"] is True


def test_id_card_marks_invalid_checksum_and_conflicting_birth_for_confirmation():
    raw = {
        "ocrType": "ID_CARD",
        "confidence": 0.96,
        "fields": [
            {"fieldKey": "birthDate", "fieldName": "出生日期", "fieldValue": "1950-01-01", "confidence": 0.96},
            {"fieldKey": "idNumber", "fieldName": "身份证号", "fieldValue": "110105194912310021", "confidence": 0.96},
        ],
        "extras": {},
    }

    data = _recognize("ID_CARD", raw)
    fields = {field.fieldKey: field for field in data.fields}

    assert fields["idNumber"].manualConfirmationRequired is True
    assert fields["birthDate"].manualConfirmationRequired is True
    assert data.extras["validation"]["idNumberValid"] is False
    assert data.extras["validation"]["birthDateConsistent"] is False


def test_id_card_accepts_non_zero_padded_chinese_birth_date():
    raw = {
        "ocrType": "ID_CARD",
        "confidence": 0.96,
        "fields": [
            {"fieldKey": "birthDate", "fieldName": "出生日期", "fieldValue": "1992年1月18日", "confidence": 0.96},
            {"fieldKey": "idNumber", "fieldName": "身份证号", "fieldValue": "510302199201182323", "confidence": 0.96},
        ],
        "extras": {},
    }

    data = _recognize("ID_CARD", raw)
    fields = {field.fieldKey: field for field in data.fields}

    assert data.extras["validation"]["birthDateConsistent"] is True
    assert fields["birthDate"].manualConfirmationRequired is False


@pytest.mark.parametrize("ocr_type,required_key", [
    ("PASSPORT", "passportNumber"),
    ("TRAVEL_PERMIT", "documentNumber"),
    ("FIVE_STAR_CARD", "permanentResidentId"),
    ("CONTRACT", "contractNumber"),
])
def test_specialized_document_types_have_explicit_field_contracts(ocr_type, required_key):
    data = _recognize(ocr_type, {"ocrType": ocr_type, "fields": [], "extras": {}})

    assert data.ocrType == ocr_type
    assert required_key in {field.fieldKey for field in data.fields}


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
    assert data.fields[0].confidence < 0.5
    assert data.fields[0].confirmationReason == "LOW_CONFIDENCE"
    assert data.extras["validation"]["plateNumberValid"] is False


def test_license_plate_preserves_multiple_targets_instead_of_merging_numbers():
    raw = {
        "ocrType": "LICENSE_PLATE",
        "confidence": 0.92,
        "fields": [
            {"fieldKey": "plateNumber", "fieldName": "车牌号", "fieldValue": "京 A·12345", "confidence": 0.95},
        ],
        "extras": {"plates": [
            {"number": "京 A·12345", "confidence": 0.95, "bbox": [10, 20, 110, 60]},
            {"number": "粤 B·D12345", "confidence": 0.91, "bbox": [150, 25, 260, 65]},
        ]},
    }

    data = _recognize("LICENSE_PLATE", raw)

    assert [item["number"] for item in data.extras["plates"]] == ["京A12345", "粤BD12345"]
    assert data.extras["plates"][0]["bbox"] == [10, 20, 110, 60]
    assert data.extras["plates"][1]["valid"] is True
    assert data.extras["validation"]["plateNumberValid"] is True
    assert data.extras["validation"]["plateNumberNormalized"] == "京A12345,粤BD12345"


def test_license_plate_multi_target_normalization_ignores_malformed_entries_safely():
    raw = {
        "ocrType": "LICENSE_PLATE",
        "confidence": 0.86,
        "fields": [
            {"fieldKey": "plateNumber", "fieldName": "车牌号", "fieldValue": "京A12345", "confidence": 0.9},
        ],
        "extras": {"plates": [
            "not-an-object",
            {"number": "ABC123", "confidence": 8, "bbox": [1, 2, 3]},
            {"number": "  ", "confidence": -1, "bbox": None},
        ]},
    }

    data = _recognize("LICENSE_PLATE", raw)

    assert data.fields[0].fieldValue == "京A12345"
    assert data.extras["plates"] == [{
        "number": "ABC123",
        "confidence": 1,
        "bbox": None,
        "valid": False,
    }]


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


def test_invoice_preserves_items_and_validates_line_and_invoice_totals():
    raw = {
        "ocrType": "INVOICE",
        "confidence": 0.96,
        "fields": [
            {"fieldKey": "invoiceType", "fieldName": "发票类型", "fieldValue": "增值税普通发票", "confidence": 0.96},
            {"fieldKey": "amountWithoutTax", "fieldName": "不含税金额", "fieldValue": "207.70", "confidence": 0.96},
            {"fieldKey": "taxAmount", "fieldName": "税额", "fieldValue": "27.00", "confidence": 0.96},
            {"fieldKey": "totalAmount", "fieldName": "价税合计", "fieldValue": "234.70", "confidence": 0.96},
        ],
        "extras": {"items": [{
            "name": "汽油92号",
            "specification": "车用汽油",
            "unit": "升",
            "quantity": "33.15",
            "unitPrice": "6.26546003017",
            "amount": "207.70",
            "taxRate": "13%",
            "taxAmount": "27.00",
            "confidence": 0.93,
        }]},
    }

    data = _recognize("INVOICE", raw, options={"invoiceType": "VAT_NORMAL"})

    assert data.extras["items"][0]["name"] == "汽油92号"
    assert data.extras["items"][0]["amountConsistent"] is True
    assert data.extras["validation"]["itemCount"] == 1
    assert data.extras["validation"]["itemAmountsConsistent"] is True
    assert data.extras["validation"]["itemTaxConsistent"] is True


def test_invoice_flags_inconsistent_items_and_safely_bounds_malformed_detail_rows():
    raw = {
        "ocrType": "INVOICE",
        "confidence": 0.95,
        "fields": [
            {"fieldKey": "invoiceType", "fieldName": "发票类型", "fieldValue": "增值税专用发票", "confidence": 0.95},
            {"fieldKey": "amountWithoutTax", "fieldName": "不含税金额", "fieldValue": "100.00", "confidence": 0.95},
            {"fieldKey": "taxAmount", "fieldName": "税额", "fieldValue": "13.00", "confidence": 0.95},
            {"fieldKey": "totalAmount", "fieldName": "价税合计", "fieldValue": "113.00", "confidence": 0.95},
        ],
        "extras": {"items": [
            "invalid-row",
            {"name": "安全帽", "quantity": "2", "unitPrice": "40", "amount": "90", "taxAmount": "12", "confidence": 4},
            *({"name": f"附加项{i}", "amount": "0", "taxAmount": "0"} for i in range(60)),
        ]},
    }

    data = _recognize("INVOICE", raw, options={"invoiceType": "VAT_SPECIAL"})
    fields = {field.fieldKey: field for field in data.fields}

    assert len(data.extras["items"]) == 50
    assert data.extras["items"][0]["confidence"] == 1
    assert data.extras["items"][0]["amountConsistent"] is False
    assert data.extras["validation"]["itemsTruncated"] is True
    assert data.extras["validation"]["itemAmountsConsistent"] is False
    assert data.extras["validation"]["itemTaxConsistent"] is False
    assert fields["amountWithoutTax"].manualConfirmationRequired is True
    assert fields["taxAmount"].manualConfirmationRequired is True


def test_custom_fields_reject_duplicate_keys_and_invalid_schema_before_model_call():
    class NeverCalled:
        async def vision_json_chat(self, *args):
            raise AssertionError("model must not receive invalid field definitions")

    request = OcrRecognizeRequest(
        projectId=1, recordId=1, ocrType="CUSTOM",
        options={"customFields": [
            {"fieldKey": "partyA", "fieldName": "甲方", "valueType": "TEXT"},
            {"fieldKey": "partyA", "fieldName": "乙方", "valueType": "TEXT"},
        ]},
        file=OcrFilePayload(fileId=1, fileName="contract.jpg", contentType="image/jpeg", dataUrls=[_image_url()]),
    )

    try:
        asyncio.run(OcrService(NeverCalled()).recognize(request))
        assert False, "duplicate custom fields must be rejected"
    except ValueError as exc:
        assert "duplicate" in str(exc)


def test_custom_fields_accept_bounded_types_and_preserve_order():
    fields = [
        {"fieldKey": "partyA", "fieldName": "甲方", "description": "合同甲方", "required": True, "valueType": "TEXT"},
        {"fieldKey": "amount", "fieldName": "金额", "description": "合同金额", "required": False, "valueType": "AMOUNT"},
    ]
    data = _recognize("CUSTOM", {"ocrType": "CUSTOM", "fields": [], "extras": {}}, options={"customFields": fields})
    assert [field.fieldKey for field in data.fields] == ["partyA", "amount"]
