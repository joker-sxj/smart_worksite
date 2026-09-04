from decimal import Decimal, InvalidOperation
import re
from typing import Any

from app.models.schemas import OcrRecognizeRequest, OcrRecognizeData, OcrFieldData
from .qwen_client import QwenClient
from .normalization import as_dict, optional_int, optional_string
from .id_card_preprocessor import IdCardPreprocessor


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
}


class OcrService:
    def __init__(self, qwen: QwenClient):
        self.qwen = qwen

    async def recognize(self, request: OcrRecognizeRequest) -> tuple[OcrRecognizeData, dict[str, Any]]:
        ocr_type = self._normalize_type(request.ocrType)
        field_definitions = self._field_definitions(request, ocr_type)
        prompt = self._build_prompt(request, ocr_type, field_definitions)
        file_sources = request.file.dataUrls or ([request.file.downloadUrl] if request.file.downloadUrl else [])
        if not file_sources:
            raise ValueError("OCR file requires dataUrls or downloadUrl")
        first_sources = file_sources
        prepared = None
        if ocr_type == "ID_CARD" and all(source.startswith("data:image/") for source in file_sources):
            try:
                prepared = IdCardPreprocessor().prepare(file_sources)
                first_sources = prepared.original_sources
            except ValueError:
                # Keep provider compatibility for opaque test/legacy data URLs; real image inputs use both passes.
                prepared = None
        raw, usage = await self.qwen.vision_json_chat(
            prompt,
            first_sources,
            request.file.contentType,
        )
        data = self._normalize_response(raw, ocr_type, field_definitions)
        data = self._apply_type_validation(data, request.options)
        if prepared is not None:
            enhanced_sources = prepared.enhanced_sources
            enhanced_raw, _ = await self.qwen.vision_json_chat(prompt, enhanced_sources, request.file.contentType)
            data = self._merge_dual_pass(data, self._normalize_response(enhanced_raw, ocr_type, field_definitions))
            usage = dict(usage)
            usage["ocrPasses"] = 2
        return data, usage

    def _apply_type_validation(self, data: OcrRecognizeData, options: dict[str, Any]) -> OcrRecognizeData:
        if data.ocrType == "LICENSE_PLATE":
            return self._validate_license_plate(data)
        if data.ocrType == "INVOICE":
            return self._validate_invoice(data, options)
        return data

    def _validate_license_plate(self, data: OcrRecognizeData) -> OcrRecognizeData:
        fields = list(data.fields)
        plate_index = next((index for index, field in enumerate(fields) if field.fieldKey == "plateNumber"), None)
        if plate_index is None:
            return data
        plate = fields[plate_index]
        normalized = re.sub(r"[\s·•・.\-]", "", plate.fieldValue).upper()
        pattern = re.compile(
            r"^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领]"
            r"[A-Z](?:[A-HJ-NP-Z0-9]{5}|[DF][A-HJ-NP-Z0-9][0-9]{4}|[0-9]{5}[DF])$"
        )
        valid = bool(pattern.fullmatch(normalized))
        fields[plate_index] = plate.model_copy(update={
            "fieldValue": normalized,
            "recognized": bool(normalized),
            "manualConfirmationRequired": plate.manualConfirmationRequired or not valid,
        })
        extras = dict(data.extras)
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
        type_consistent = bool(expected_label and expected_label in actual_type)
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
        validation = as_dict(extras.get("validation"))
        validation.update({
            "invoiceTypeConsistent": type_consistent,
            "amountsAvailable": amounts_available,
            "amountsConsistent": amounts_consistent if amounts_available else None,
        })
        extras["validation"] = validation
        return data.model_copy(update={"fields": fields, "extras": extras})

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
        for left, right in zip(original.fields, enhanced.fields):
            left_value = left.fieldValue.strip()
            right_value = right.fieldValue.strip()
            if left_value and right_value and left_value == right_value:
                merged.append(left.model_copy(update={"manualConfirmationRequired": False}))
            elif not left_value and not right_value:
                merged.append(left.model_copy(update={"manualConfirmationRequired": True}))
            else:
                conflicts.append(left.fieldKey)
                merged.append(left.model_copy(update={
                    "fieldValue": "", "confidence": 0, "recognized": False,
                    "manualConfirmationRequired": True,
                }))
        extras = dict(original.extras)
        extras["dualPass"] = {"conflicts": conflicts, "preprocessing": "orientation_contrast_sharpness"}
        return original.model_copy(update={"fields": merged, "extras": extras})

    def _normalize_type(self, ocr_type: str) -> str:
        normalized = (ocr_type or "").upper()
        if normalized == "CONTRACT":
            return "CUSTOM"
        if normalized not in {"ID_CARD", "LICENSE_PLATE", "INVOICE", "CUSTOM"}:
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
            "LICENSE_PLATE": "仅在extras.plate中返回number、backgroundColor、fontColor、plateType、bbox；extras不要包含其他类型结构。",
            "INVOICE": "仅在extras.items中返回最多50条可见明细，并在extras.validation中返回金额校验结果。",
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
            "\"extras\":{},"
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
