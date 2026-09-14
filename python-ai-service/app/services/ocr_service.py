from decimal import Decimal, InvalidOperation
from datetime import datetime
import re
import unicodedata
from typing import Any

from app.models.schemas import OcrRecognizeRequest, OcrRecognizeData, OcrFieldCandidate, OcrFieldData
from .qwen_client import QwenClient
from .normalization import as_dict, optional_int, optional_string
from .id_card_preprocessor import IdCardPreprocessor
from .ocr_provider import build_ocr_provider


STANDARD_FIELDS: dict[str, list[dict[str, Any]]] = {
    "ID_CARD": [
        {"fieldKey": "name", "fieldName": "姓名"},
        {"fieldKey": "gender", "fieldName": "性别"},
        {"fieldKey": "nation", "fieldName": "民族"},
        {"fieldKey": "birthDate", "fieldName": "出生日期", "aliases": ["出生", "出生年月日"]},
        {"fieldKey": "address", "fieldName": "住址", "aliases": ["地址"]},
        {
            "fieldKey": "idNumber",
            "fieldName": "身份证号",
            "aliases": ["公民身份号码", "身份证号码", "身份号码"],
        },
        {"fieldKey": "issuingAuthority", "fieldName": "签发机关"},
        {"fieldKey": "validPeriod", "fieldName": "有效期限", "aliases": ["有效期"]},
        {"fieldKey": "hasWatermark", "fieldName": "是否有水印", "aliases": ["水印"]},
    ],
    "LICENSE_PLATE": [
        {"fieldKey": "plateNumber", "fieldName": "车牌号"},
        {"fieldKey": "backgroundColor", "fieldName": "底色"},
        {"fieldKey": "fontColor", "fieldName": "字号颜色"},
        {"fieldKey": "plateType", "fieldName": "车牌类型"},
    ],
    "INVOICE": [
        {"fieldKey": "invoiceType", "fieldName": "发票类型"},
        {"fieldKey": "invoiceCode", "fieldName": "发票代码"},
        {"fieldKey": "invoiceNumber", "fieldName": "发票号码"},
        {"fieldKey": "issueDate", "fieldName": "开票日期"},
        {"fieldKey": "buyerName", "fieldName": "购买方名称"},
        {"fieldKey": "buyerTaxNumber", "fieldName": "购买方纳税人识别号"},
        {"fieldKey": "sellerName", "fieldName": "销售方名称"},
        {"fieldKey": "sellerTaxNumber", "fieldName": "销售方纳税人识别号"},
        {"fieldKey": "amountWithoutTax", "fieldName": "不含税金额"},
        {"fieldKey": "taxAmount", "fieldName": "税额"},
        {"fieldKey": "totalAmount", "fieldName": "价税合计"},
    ],
    "PASSPORT": [
        {"fieldKey": "passportNumber", "fieldName": "护照号码"},
        {"fieldKey": "name", "fieldName": "姓名"},
        {"fieldKey": "nationality", "fieldName": "国籍"},
        {"fieldKey": "gender", "fieldName": "性别"},
        {"fieldKey": "birthDate", "fieldName": "出生日期"},
        {"fieldKey": "placeOfBirth", "fieldName": "出生地点"},
        {"fieldKey": "issueDate", "fieldName": "签发日期"},
        {"fieldKey": "expiryDate", "fieldName": "有效期至"},
        {"fieldKey": "issuingAuthority", "fieldName": "签发机关"},
        {"fieldKey": "mrz", "fieldName": "机读码"},
    ],
    "TRAVEL_PERMIT": [
        {"fieldKey": "documentNumber", "fieldName": "证件号码"},
        {"fieldKey": "name", "fieldName": "姓名"},
        {"fieldKey": "gender", "fieldName": "性别"},
        {"fieldKey": "birthDate", "fieldName": "出生日期"},
        {"fieldKey": "validPeriod", "fieldName": "有效期限"},
        {"fieldKey": "issueCount", "fieldName": "签发次数"},
        {"fieldKey": "issuingAuthority", "fieldName": "签发机关"},
    ],
    "FIVE_STAR_CARD": [
        {"fieldKey": "permanentResidentId", "fieldName": "永久居留证件号码"},
        {"fieldKey": "name", "fieldName": "姓名"},
        {"fieldKey": "gender", "fieldName": "性别"},
        {"fieldKey": "birthDate", "fieldName": "出生日期"},
        {"fieldKey": "nationality", "fieldName": "国籍"},
        {"fieldKey": "validPeriod", "fieldName": "有效期限"},
        {"fieldKey": "issuingAuthority", "fieldName": "签发机关"},
    ],
    "CONTRACT": [
        {"fieldKey": "contractNumber", "fieldName": "合同编号"},
        {"fieldKey": "partyA", "fieldName": "甲方"},
        {"fieldKey": "partyB", "fieldName": "乙方"},
        {"fieldKey": "contractAmount", "fieldName": "合同金额"},
        {"fieldKey": "paymentTerms", "fieldName": "付款条件"},
        {"fieldKey": "signDate", "fieldName": "签订日期"},
        {"fieldKey": "effectiveDate", "fieldName": "生效日期"},
        {"fieldKey": "projectName", "fieldName": "项目名称"},
    ],
}


class OcrValueNormalizer:
    DATE_FIELDS = {"birthDate", "issueDate", "expiryDate", "signDate", "effectiveDate"}
    AMOUNT_FIELDS = {"amountWithoutTax", "taxAmount", "totalAmount", "contractAmount"}
    DOCUMENT_NUMBER_FIELDS = {
        "idNumber", "passportNumber", "documentNumber", "permanentResidentId",
        "plateNumber", "invoiceCode", "invoiceNumber", "buyerTaxNumber", "sellerTaxNumber",
        "contractNumber",
    }

    @classmethod
    def canonical(cls, field_key: str, value: str) -> str | None:
        normalized = unicodedata.normalize("NFKC", str(value or "")).strip()
        if not normalized:
            return ""
        if field_key in cls.DATE_FIELDS:
            return cls.date_digits(normalized)
        if field_key in cls.AMOUNT_FIELDS:
            amount = re.sub(r"[^0-9.\-]", "", normalized)
            try:
                return str(Decimal(amount).normalize()) if amount else None
            except InvalidOperation:
                return None
        if field_key in cls.DOCUMENT_NUMBER_FIELDS:
            return "".join(character for character in normalized.upper() if character.isalnum())
        return "".join(
            character for character in normalized.casefold()
            if not character.isspace() and not unicodedata.category(character).startswith(("P", "S"))
        )

    @staticmethod
    def date_digits(value: str) -> str | None:
        text = unicodedata.normalize("NFKC", str(value or "")).strip()
        compact = re.sub(r"\D", "", text)
        if len(compact) == 8:
            try:
                datetime.strptime(compact, "%Y%m%d")
                return compact
            except ValueError:
                return None
        match = re.search(r"(\d{4})\D+(\d{1,2})\D+(\d{1,2})", text)
        if not match:
            return None
        normalized = f"{match.group(1)}{int(match.group(2)):02d}{int(match.group(3)):02d}"
        try:
            datetime.strptime(normalized, "%Y%m%d")
            return normalized
        except ValueError:
            return None


class OcrService:
    def __init__(self, qwen: QwenClient, ocr_provider=None):
        self.qwen = qwen
        self.ocr_provider = ocr_provider

    async def recognize(self, request: OcrRecognizeRequest) -> tuple[OcrRecognizeData, dict[str, Any]]:
        ocr_type = self._normalize_type(request.ocrType)
        field_definitions = self._field_definitions(request, ocr_type)
        prompt = self._build_prompt(request, ocr_type, field_definitions)
        file_sources = request.file.dataUrls or ([request.file.downloadUrl] if request.file.downloadUrl else [])
        if not file_sources:
            raise ValueError("OCR file requires dataUrls or downloadUrl")
        provider = self.ocr_provider or build_ocr_provider()
        local_text = ""
        local_usage: dict[str, Any] = {}
        if file_sources and all(source.startswith("data:image/") for source in file_sources):
            try:
                import asyncio
                local_result = await asyncio.to_thread(provider.recognize, file_sources)
                local_text = local_result.text
                local_usage = {
                    "ocrProvider": local_result.provider,
                    "ocrModel": local_result.model,
                    "ocrLineCount": len(local_result.lines),
                }
            except (RuntimeError, ValueError):
                if provider.provider_name != "QWEN_VL":
                    raise
        first_sources = file_sources
        prepared = None
        if ocr_type == "ID_CARD" and all(source.startswith("data:image/") for source in file_sources):
            try:
                prepared = IdCardPreprocessor().prepare(file_sources)
                first_sources = prepared.original_sources
            except ValueError:
                # Keep provider compatibility for opaque test/legacy data URLs; real image inputs use both passes.
                prepared = None
        if local_text:
            prompt += f"\n本地字符OCR初步结果（仅作证据，不得盲目信任）：{local_text[:12000]}"
        raw, usage = await self.qwen.vision_json_chat(
            prompt,
            first_sources,
            request.file.contentType,
        )
        data = self._normalize_response(raw, ocr_type, field_definitions)
        if prepared is not None:
            enhanced_sources = prepared.enhanced_sources
            enhanced_raw, _ = await self.qwen.vision_json_chat(prompt, enhanced_sources, request.file.contentType)
            data = self._merge_dual_pass(data, self._normalize_response(enhanced_raw, ocr_type, field_definitions))
            usage = dict(usage)
            usage["ocrPasses"] = 2
        data = self._apply_type_validation(data, request.options)
        data = self._apply_document_diagnostics(data, ocr_type)
        usage = {**usage, **local_usage}
        return data, usage

    def _apply_document_diagnostics(self, data: OcrRecognizeData, selected_type: str) -> OcrRecognizeData:
        extras = dict(data.extras)
        indicators = extras.get("visualIndicators")
        indicator_text = " ".join(str(item) for item in indicators if isinstance(item, str)) if isinstance(indicators, list) else ""
        field_text = " ".join(
            value for field in data.fields for value in (field.fieldValue, field.evidence or "") if value
        )
        detected_type, confidence = self._detect_document_type(f"{indicator_text} {field_text}")
        mismatch = detected_type != "UNKNOWN" and detected_type != selected_type
        # Keep legacy responses stable when the provider supplies no type evidence at all.
        if detected_type != "UNKNOWN" or indicator_text or "documentType" in extras:
            extras["documentType"] = {
                "selectedType": selected_type,
                "detectedType": detected_type,
                "confidence": confidence,
                "mismatch": mismatch,
            }
        sides = {str(side).upper() for side in extras.get("sideDetected", [])} if isinstance(extras.get("sideDetected"), list) else set()
        masked_fields = {str(key) for key in extras.get("maskedFields", [])} if isinstance(extras.get("maskedFields"), list) else set()
        missing_back_fields = {"issuingAuthority", "validPeriod"}
        fields: list[OcrFieldData] = []
        for field in data.fields:
            reason = field.confirmationReason
            value = field.fieldValue.strip()
            if mismatch:
                reason = "TYPE_MISMATCH"
            elif field.fieldKey in masked_fields or (value and re.fullmatch(r"[*＊xX×·•]{2,}", value)):
                reason = "SOURCE_MASKED"
            elif selected_type == "ID_CARD" and sides == {"FRONT"} and field.fieldKey in missing_back_fields and not value:
                reason = "MISSING_SIDE_OR_PAGE"
            elif field.manualConfirmationRequired and not reason:
                reason = "LOW_CONFIDENCE" if value else "FIELD_NOT_VISIBLE"
            fields.append(field.model_copy(update={
                "manualConfirmationRequired": field.manualConfirmationRequired or mismatch or bool(reason),
                "confirmationReason": reason,
            }))
        return data.model_copy(update={"fields": fields, "extras": extras})

    def _detect_document_type(self, text: str) -> tuple[str, float]:
        compact = unicodedata.normalize("NFKC", text or "").upper()
        rules = (
            ("FIVE_STAR_CARD", ("外国人永久居留身份证", "FOREIGN PERMANENT RESIDENT ID CARD")),
            ("TRAVEL_PERMIT", ("往来港澳通行证", "港澳居民来往内地通行证", "台湾居民来往大陆通行证")),
            ("INVOICE", ("增值税电子普通发票", "增值税专用发票", "电子发票（普通发票）")),
            ("ID_CARD", ("中华人民共和国居民身份证", "RESIDENT IDENTITY CARD")),
            ("PASSPORT", ("中华人民共和国护照", "PEOPLE'S REPUBLIC OF CHINA PASSPORT")),
        )
        for document_type, markers in rules:
            if any(marker in compact for marker in markers):
                return document_type, 0.98
        # A valid two-line ICAO MRZ is a deterministic passport indicator.
        mrz_lines = [re.sub(r"\s", "", line) for line in compact.splitlines()]
        if any(re.fullmatch(r"P<[A-Z]{3}[A-Z<]{20,}", line) for line in mrz_lines):
            return "PASSPORT", 0.99
        contract_markers = sum(marker in compact for marker in ("合同编号", "甲方", "乙方", "付款条款"))
        if contract_markers >= 3:
            return "CONTRACT", 0.95
        return "UNKNOWN", 0.0

    def _apply_type_validation(self, data: OcrRecognizeData, options: dict[str, Any]) -> OcrRecognizeData:
        if data.ocrType == "ID_CARD":
            return self._validate_id_card(data)
        if data.ocrType == "LICENSE_PLATE":
            return self._validate_license_plate(data)
        if data.ocrType == "INVOICE":
            return self._validate_invoice(data, options)
        return data

    def _validate_id_card(self, data: OcrRecognizeData) -> OcrRecognizeData:
        fields = list(data.fields)
        by_key = {field.fieldKey: index for index, field in enumerate(fields)}
        id_index = by_key.get("idNumber")
        birth_index = by_key.get("birthDate")
        if id_index is None:
            return data
        id_value = re.sub(r"[\s-]", "", fields[id_index].fieldValue).upper() if id_index is not None else ""
        if not id_value:
            return data
        structurally_valid = bool(re.fullmatch(r"\d{17}[0-9X]", id_value))
        id_valid = self._is_valid_id_number(id_value)
        if id_index is not None:
            fields[id_index] = fields[id_index].model_copy(update={
                "fieldValue": id_value or fields[id_index].fieldValue,
                "manualConfirmationRequired": fields[id_index].manualConfirmationRequired or not id_valid,
            })
        birth_consistent = None
        if structurally_valid and birth_index is not None:
            birth_value = self._normalized_date_digits(fields[birth_index].fieldValue)
            birth_consistent = birth_value == id_value[6:14]
            if not birth_consistent:
                fields[birth_index] = fields[birth_index].model_copy(update={"manualConfirmationRequired": True})
        extras = dict(data.extras)
        validation = as_dict(extras.get("validation"))
        validation.update({"idNumberValid": id_valid, "birthDateConsistent": birth_consistent})
        extras["validation"] = validation
        return data.model_copy(update={"fields": fields, "extras": extras})

    def _normalized_date_digits(self, value: str) -> str | None:
        text = str(value or "").strip()
        compact = re.sub(r"\D", "", text)
        if len(compact) == 8:
            try:
                datetime.strptime(compact, "%Y%m%d")
                return compact
            except ValueError:
                return None
        match = re.search(r"(\d{4})\D+(\d{1,2})\D+(\d{1,2})", text)
        if not match:
            return None
        normalized = f"{match.group(1)}{int(match.group(2)):02d}{int(match.group(3)):02d}"
        try:
            datetime.strptime(normalized, "%Y%m%d")
            return normalized
        except ValueError:
            return None

    def _validate_license_plate(self, data: OcrRecognizeData) -> OcrRecognizeData:
        fields = list(data.fields)
        plate_index = next((index for index, field in enumerate(fields) if field.fieldKey == "plateNumber"), None)
        if plate_index is None:
            return data
        plate = fields[plate_index]
        normalized = re.sub(r"[\s·•・.\-]", "", plate.fieldValue).upper()
        extras = dict(data.extras)
        plates = extras.get("plates")
        normalized_plates = []
        if isinstance(plates, list):
            for item in plates:
                if not isinstance(item, dict):
                    continue
                number = re.sub(r"[\s·•・.\-]", "", str(item.get("number") or "")).upper()
                if not number:
                    continue
                normalized_item = dict(item)
                normalized_item["number"] = number
                normalized_item["valid"] = self._is_valid_plate_number(number)
                normalized_item["confidence"] = self._confidence(item.get("confidence"))
                bbox = item.get("bbox")
                normalized_item["bbox"] = bbox if isinstance(bbox, list) and len(bbox) == 4 else None
                normalized_plates.append(normalized_item)
            extras["plates"] = normalized_plates
        if len(normalized_plates) > 1:
            normalized = ",".join(item["number"] for item in normalized_plates)
            valid = all(item["valid"] for item in normalized_plates)
        else:
            valid = self._is_valid_plate_number(normalized)
        fields[plate_index] = plate.model_copy(update={
            "fieldValue": normalized,
            "recognized": bool(normalized),
            "confidence": plate.confidence if valid else min(plate.confidence, 0.49),
            "manualConfirmationRequired": plate.manualConfirmationRequired or not valid,
        })
        validation = as_dict(extras.get("validation"))
        validation["plateNumberValid"] = valid
        validation["plateNumberNormalized"] = normalized
        extras["validation"] = validation
        return data.model_copy(update={"fields": fields, "extras": extras})

    def _validate_invoice(self, data: OcrRecognizeData, options: dict[str, Any]) -> OcrRecognizeData:
        fields = list(data.fields)
        by_key = {field.fieldKey: index for index, field in enumerate(fields)}
        expected_type = str(options.get("invoiceType") or "").strip().upper()
        actual_type = fields[by_key["invoiceType"]].fieldValue if "invoiceType" in by_key else ""
        expected_label = {"VAT_SPECIAL": "专用", "VAT_NORMAL": "普通"}.get(expected_type)
        type_consistent = bool(
            expected_type
            and (actual_type.strip().upper() == expected_type or (expected_label and expected_label in actual_type))
        )
        if "invoiceType" in by_key and not type_consistent:
            index = by_key["invoiceType"]
            fields[index] = fields[index].model_copy(update={"manualConfirmationRequired": True})

        amount_keys = ("amountWithoutTax", "taxAmount", "totalAmount")
        amounts = {key: self._money(fields[by_key[key]].fieldValue) for key in amount_keys if key in by_key}
        amounts_available = len(amounts) == len(amount_keys) and all(value is not None for value in amounts.values())
        amounts_consistent = bool(
            amounts_available
            and abs(amounts["amountWithoutTax"] + amounts["taxAmount"] - amounts["totalAmount"]) <= Decimal("0.01")
        )
        if amounts_available and not amounts_consistent:
            for key in amount_keys:
                index = by_key[key]
                fields[index] = fields[index].model_copy(update={"manualConfirmationRequired": True})

        extras = dict(data.extras)
        items, item_validation = self._normalize_invoice_items(extras.get("items"), amounts)
        extras["items"] = items
        if item_validation["itemAmountsConsistent"] is False and "amountWithoutTax" in by_key:
            index = by_key["amountWithoutTax"]
            fields[index] = fields[index].model_copy(update={"manualConfirmationRequired": True})
        if item_validation["itemTaxConsistent"] is False and "taxAmount" in by_key:
            index = by_key["taxAmount"]
            fields[index] = fields[index].model_copy(update={"manualConfirmationRequired": True})
        validation = as_dict(extras.get("validation"))
        validation.update({
            "invoiceTypeConsistent": type_consistent,
            "amountsAvailable": amounts_available,
            "amountsConsistent": amounts_consistent if amounts_available else None,
            **item_validation,
        })
        extras["validation"] = validation
        return data.model_copy(update={"fields": fields, "extras": extras})

    def _normalize_invoice_items(
        self,
        raw_items: Any,
        header_amounts: dict[str, Decimal | None],
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        source_items = [item for item in raw_items if isinstance(item, dict)] if isinstance(raw_items, list) else []
        normalized_items: list[dict[str, Any]] = []
        line_amount_checks: list[bool] = []
        line_tax_checks: list[bool] = []
        item_amounts: list[Decimal] = []
        item_taxes: list[Decimal] = []
        all_amounts_available = True
        all_taxes_available = True

        for source in source_items[:50]:
            item = dict(source)
            item["confidence"] = self._confidence(source.get("confidence"))
            quantity = self._money(str(source.get("quantity") or ""))
            unit_price = self._money(str(source.get("unitPrice") or ""))
            amount = self._money(str(source.get("amount") or ""))
            tax_amount = self._money(str(source.get("taxAmount") or ""))
            tax_rate = self._rate(source.get("taxRate"))

            amount_check = None
            if quantity is not None and unit_price is not None and amount is not None:
                amount_check = abs(quantity * unit_price - amount) <= Decimal("0.01")
                line_amount_checks.append(amount_check)
            tax_check = None
            if amount is not None and tax_rate is not None and tax_amount is not None:
                tax_check = abs(amount * tax_rate - tax_amount) <= Decimal("0.01")
                line_tax_checks.append(tax_check)
            item["amountConsistent"] = amount_check
            item["taxConsistent"] = tax_check
            normalized_items.append(item)

            if amount is None:
                all_amounts_available = False
            else:
                item_amounts.append(amount)
            if tax_amount is None:
                all_taxes_available = False
            else:
                item_taxes.append(tax_amount)

        header_amount = header_amounts.get("amountWithoutTax")
        aggregate_amount_check = (
            abs(sum(item_amounts, Decimal("0")) - header_amount) <= Decimal("0.01")
            if normalized_items and all_amounts_available and header_amount is not None else None
        )
        header_tax = header_amounts.get("taxAmount")
        aggregate_tax_check = (
            abs(sum(item_taxes, Decimal("0")) - header_tax) <= Decimal("0.01")
            if normalized_items and all_taxes_available and header_tax is not None else None
        )
        return normalized_items, {
            "itemCount": len(normalized_items),
            "itemsTruncated": len(source_items) > 50,
            "itemAmountsConsistent": self._combine_checks(line_amount_checks, aggregate_amount_check),
            "itemTaxConsistent": self._combine_checks(line_tax_checks, aggregate_tax_check),
        }

    def _combine_checks(self, line_checks: list[bool], aggregate_check: bool | None) -> bool | None:
        checks = [*line_checks, *([aggregate_check] if aggregate_check is not None else [])]
        return all(checks) if checks else None

    def _rate(self, value: Any) -> Decimal | None:
        text = str(value or "").strip()
        if not text:
            return None
        normalized = text[:-1] if text.endswith("%") else text
        try:
            rate = Decimal(normalized)
        except InvalidOperation:
            return None
        if text.endswith("%") or rate > 1:
            rate /= Decimal("100")
        return rate if Decimal("0") <= rate <= Decimal("1") else None

    def _money(self, value: str) -> Decimal | None:
        normalized = re.sub(r"[^0-9.\-]", "", value or "")
        if not normalized:
            return None
        try:
            return Decimal(normalized)
        except InvalidOperation:
            return None

    def _merge_dual_pass(self, original: OcrRecognizeData, enhanced: OcrRecognizeData) -> OcrRecognizeData:
        conflicts: list[str] = []
        merged: list[OcrFieldData] = []
        enhanced_by_key = {field.fieldKey: field for field in enhanced.fields}
        for left in original.fields:
            right = enhanced_by_key.get(left.fieldKey, OcrFieldData(fieldKey=left.fieldKey, fieldName=left.fieldName))
            left_value, right_value = left.fieldValue.strip(), right.fieldValue.strip()
            left_canonical = OcrValueNormalizer.canonical(left.fieldKey, left_value)
            right_canonical = OcrValueNormalizer.canonical(right.fieldKey, right_value)
            if left_canonical and left_canonical == right_canonical:
                selected = left if (left.confidence, len(left_value)) >= (right.confidence, len(right_value)) else right
                low_confidence = left.confidence < 0.5 and right.confidence < 0.5
                merged.append(selected.model_copy(update={
                    "manualConfirmationRequired": low_confidence,
                    "confirmationReason": "LOW_CONFIDENCE" if low_confidence else None,
                    "candidates": [],
                }))
                continue
            if not left_value and not right_value:
                merged.append(left.model_copy(update={"manualConfirmationRequired": True, "confirmationReason": "FIELD_NOT_VISIBLE"}))
                continue
            if not left_value or not right_value:
                selected = left if left_value else right
                merged.append(selected.model_copy(update={
                    "manualConfirmationRequired": True,
                    "confirmationReason": "DUAL_PASS_CONFLICT",
                    "candidates": [],
                }))
                continue
            valid_left = self._candidate_valid(left)
            valid_right = self._candidate_valid(right)
            if valid_left != valid_right:
                selected = left if valid_left else right
                low_confidence = selected.confidence < 0.5
                merged.append(selected.model_copy(update={
                    "manualConfirmationRequired": low_confidence,
                    "confirmationReason": "LOW_CONFIDENCE" if low_confidence else None,
                    "candidates": [],
                }))
                continue
            conflicts.append(left.fieldKey)
            candidates = [
                OcrFieldCandidate(value=left_value, confidence=left.confidence, evidence=left.evidence, source="ORIGINAL"),
                OcrFieldCandidate(value=right_value, confidence=right.confidence, evidence=right.evidence, source="ENHANCED"),
            ]
            merged.append(left.model_copy(update={
                "fieldValue": "", "confidence": 0, "recognized": False,
                "manualConfirmationRequired": True, "confirmationReason": "DUAL_PASS_CONFLICT",
                "candidates": candidates,
            }))
        extras = dict(original.extras)
        extras["dualPass"] = {"conflicts": conflicts, "preprocessing": "orientation_contrast_sharpness"}
        return original.model_copy(update={"fields": merged, "extras": extras})

    def _candidate_valid(self, field: OcrFieldData) -> bool:
        value = field.fieldValue
        if field.fieldKey == "idNumber":
            return self._is_valid_id_number(value)
        if field.fieldKey == "plateNumber":
            return self._is_valid_plate_number(value)
        if field.fieldKey in OcrValueNormalizer.DATE_FIELDS:
            return OcrValueNormalizer.date_digits(value) is not None
        if field.fieldKey in OcrValueNormalizer.AMOUNT_FIELDS:
            return self._money(value) is not None
        return False

    def _is_valid_id_number(self, value: str) -> bool:
        canonical = re.sub(r"[\s-]", "", value).upper()
        if not re.fullmatch(r"\d{17}[0-9X]", canonical):
            return False
        try:
            datetime.strptime(canonical[6:14], "%Y%m%d")
        except ValueError:
            return False
        weights = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        return "10X98765432"[sum(int(v) * w for v, w in zip(canonical[:17], weights)) % 11] == canonical[-1]

    def _is_valid_plate_number(self, value: str) -> bool:
        canonical = re.sub(r"[\s·•・.\-]", "", value).upper()
        pattern = (
            r"^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领]"
            r"[A-Z](?:[A-HJ-NP-Z0-9]{5}|[DF][A-HJ-NP-Z0-9][0-9]{4}|[0-9]{5}[DF])$"
        )
        return bool(re.fullmatch(pattern, canonical))

    def _normalize_type(self, ocr_type: str) -> str:
        normalized = (ocr_type or "").upper()
        if normalized not in {"ID_CARD", "LICENSE_PLATE", "INVOICE", "PASSPORT", "TRAVEL_PERMIT", "FIVE_STAR_CARD", "CONTRACT", "CUSTOM"}:
            raise ValueError("unsupported ocrType")
        return normalized

    def _build_prompt(
        self,
        request: OcrRecognizeRequest,
        ocr_type: str,
        fields: list[dict[str, Any]] | None = None,
    ) -> str:
        fields = fields or self._field_definitions(request, ocr_type)
        type_instruction = {
            "ID_CARD": "身份证正反面字段都必须保留；仅在extras.watermark中返回detected、type、text、confidence；extras不要包含其他类型结构。",
            "LICENSE_PLATE": "单车牌在extras.plate中返回number、backgroundColor、fontColor、plateType、bbox；检测到多个车牌时还必须在extras.plates数组中逐目标返回，禁止把多个号码拼接为一个字符串。",
            "INVOICE": "仅在extras.items中返回最多50条可见明细；每条明细尽量返回name、specification、unit、quantity、unitPrice、amount、taxRate、taxAmount、confidence，并在extras.validation中返回明细金额和税额校验结果。",
            "PASSPORT": "核对护照资料页和机读码；不可见字段留空，禁止根据国籍或姓名猜测。",
            "TRAVEL_PERMIT": "识别港澳台通行证可见字段；证件号码和有效期不完整时留空。",
            "FIVE_STAR_CARD": "识别外国人永久居留身份证可见字段；中英文姓名分别按证面证据抽取。",
            "CONTRACT": "从合同正文和签章页提取关键字段；金额、日期和当事方必须带证据位置。",
            "CUSTOM": "extras返回空对象，自定义字段尽量返回evidence和pageNo。",
        }[ocr_type]
        return (
            "你是智慧工地OCR字段抽取服务。请识别上传的图片或PDF页面，并严格返回JSON对象，不要返回Markdown。\n"
            f"OCR类型: {ocr_type}\n"
            f"文件名: {request.file.fileName}\n"
            f"内容类型: {request.file.contentType or 'unknown'}\n"
            f"额外选项: {request.options}\n"
            f"需要抽取的字段定义: {fields}\n"
            "fields数组必须与字段定义一一对应，数量、fieldKey、fieldName和顺序必须完全一致，不得增加、删除或重复字段。"
            "只允许输出一个紧凑JSON对象，不要输出Markdown、注释或解释。"
            "所有字符串必须使用英文双引号，evidence不要超过80个中文字符，raw固定返回空对象。\n"
            "输出JSON格式必须为: {"
            "\"ocrType\":\"...\","
            "\"confidence\":0到1之间数字,"
            "\"fields\":[{\"fieldKey\":\"...\",\"fieldName\":\"...\",\"fieldValue\":\"...\",\"confidence\":0到1之间数字,\"recognized\":true或false,\"location\":\"页码或区域\",\"pageNo\":1,\"evidence\":\"原文证据\"}],"
            "\"extras\":{\"visualIndicators\":[\"仅返回画面中逐字可见的证件标题、标签或机读码标识\"],\"sideDetected\":[\"身份证仅返回FRONT或BACK\"],\"maskedFields\":[\"仅返回确有遮挡或打码的fieldKey\"],\"visibleScope\":\"完整文档或CONTRACT_FRAGMENT\"},"
            "\"raw\":{}"
            "}。\n"
            "如果字段不可见或无法确认，仍必须返回该字段，fieldValue返回空字符串，confidence返回0，recognized返回false，不要编造。"
            + type_instruction
        )

    def _field_definitions(self, request: OcrRecognizeRequest, ocr_type: str) -> list[dict[str, Any]]:
        if ocr_type == "CUSTOM":
            fields = request.options.get("customFields") or []
            if not isinstance(fields, list) or not fields:
                raise ValueError("customFields is required for CUSTOM OCR")
            return self._normalize_field_definitions(fields)
        return self._normalize_field_definitions(STANDARD_FIELDS[ocr_type])

    def _normalize_field_definitions(self, fields: list[Any]) -> list[dict[str, Any]]:
        if len(fields) > 30:
            raise ValueError("OCR field definitions cannot exceed 30 fields")
        normalized: list[dict[str, Any]] = []
        seen_keys: set[str] = set()
        seen_names: set[str] = set()
        for item in fields:
            if not isinstance(item, dict):
                raise ValueError("OCR field definition must be an object")
            field_key = str(item.get("fieldKey") or item.get("key") or "").strip()
            field_name = str(item.get("fieldName") or item.get("name") or "").strip()
            if not field_key or not field_name:
                raise ValueError("OCR field definition requires fieldKey and fieldName")
            if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", field_key):
                raise ValueError("OCR fieldKey must use a safe identifier")
            if len(field_name) > 40 or len(str(item.get("description") or "").strip()) > 200:
                raise ValueError("OCR field definition text is too long")
            value_type = str(item.get("valueType") or "TEXT").strip().upper()
            if value_type not in {"TEXT", "DATE", "NUMBER", "AMOUNT", "BOOLEAN"}:
                raise ValueError("unsupported OCR field valueType")
            key_token = self._match_token(field_key)
            name_token = self._match_token(field_name)
            if key_token in seen_keys or name_token in seen_names:
                raise ValueError("OCR field definitions must not contain duplicate keys or names")
            seen_keys.add(key_token)
            seen_names.add(name_token)
            aliases = item.get("aliases") if isinstance(item.get("aliases"), list) else []
            normalized_item = dict(item)
            normalized_item["fieldKey"] = field_key
            normalized_item["fieldName"] = field_name
            normalized_item["valueType"] = value_type
            normalized_item["aliases"] = [str(alias).strip() for alias in aliases if str(alias).strip()]
            normalized.append(normalized_item)
        return normalized

    def _normalize_response(
        self,
        raw: dict[str, Any],
        ocr_type: str,
        definitions: list[dict[str, Any]],
    ) -> OcrRecognizeData:
        raw_fields = raw.get("fields") or []
        if not isinstance(raw_fields, list):
            raw_fields = []
        provider_raw_fields = [item for item in raw_fields if isinstance(item, dict)]
        provider_fields = [self._normalize_provider_field(item) for item in provider_raw_fields]
        required_keys = {self._match_token(item["fieldKey"]) for item in definitions}
        used_indexes: set[int] = set()
        normalized_fields: list[OcrFieldData] = []

        for definition in definitions:
            key_token = self._match_token(definition["fieldKey"])
            name_tokens = {
                self._match_token(definition["fieldName"]),
                *(self._match_token(alias) for alias in definition.get("aliases", [])),
            }
            key_matches = [
                index for index, item in enumerate(provider_fields)
                if index not in used_indexes and self._match_token(item.fieldKey) == key_token
            ]
            candidates = key_matches
            if not candidates:
                candidates = [
                    index for index, item in enumerate(provider_fields)
                    if index not in used_indexes
                    and self._match_token(item.fieldKey) not in required_keys
                    and self._match_token(item.fieldName) in name_tokens
                ]
            if candidates:
                selected_index = max(candidates, key=lambda index: provider_fields[index].confidence)
                used_indexes.update(candidates)
                selected = provider_fields[selected_index]
                normalized_fields.append(OcrFieldData(
                    fieldKey=definition["fieldKey"],
                    fieldName=definition["fieldName"],
                    fieldValue=selected.fieldValue,
                    confidence=selected.confidence,
                    recognized=bool(selected.fieldValue.strip()),
                    manualConfirmationRequired=selected.confidence < 0.5,
                    location=selected.location,
                    pageNo=selected.pageNo,
                    evidence=selected.evidence,
                ))
            else:
                normalized_fields.append(OcrFieldData(
                    fieldKey=definition["fieldKey"],
                    fieldName=definition["fieldName"],
                    fieldValue="",
                    confidence=0,
                    recognized=False,
                    manualConfirmationRequired=True,
                ))

        extras = as_dict(raw.get("extras"))
        unmapped = [provider_raw_fields[index] for index in range(len(provider_raw_fields)) if index not in used_indexes]
        if unmapped:
            extras["unmappedFields"] = unmapped
        return OcrRecognizeData(
            ocrType=ocr_type,
            confidence=self._confidence(raw.get("confidence")),
            fields=normalized_fields,
            extras=extras,
            raw=raw.get("raw") if isinstance(raw.get("raw"), dict) else {"providerJson": raw},
        )

    def _normalize_provider_field(self, item: dict[str, Any]) -> OcrFieldData:
        field_value = "" if item.get("fieldValue") is None else str(item.get("fieldValue"))
        return OcrFieldData(
            fieldKey=str(item.get("fieldKey") or item.get("key") or "").strip(),
            fieldName=str(item.get("fieldName") or item.get("name") or "").strip(),
            fieldValue=field_value,
            confidence=self._confidence(item.get("confidence")),
            recognized=bool(field_value.strip()),
            location=optional_string(item.get("location")),
            pageNo=optional_int(item.get("pageNo")),
            evidence=optional_string(item.get("evidence")),
        )

    def _match_token(self, value: Any) -> str:
        return "".join(str(value or "").split()).casefold()

    def _confidence(self, value: Any) -> float:
        try:
            number = float(value)
        except (TypeError, ValueError):
            return 0
        return max(0, min(1, number))
