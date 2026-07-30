from typing import Any

from app.models.schemas import OcrRecognizeRequest, OcrRecognizeData, OcrFieldData
from .qwen_client import QwenClient
from .normalization import as_dict, optional_int, optional_string


STANDARD_FIELDS: dict[str, list[dict[str, str]]] = {
    "ID_CARD": [
        {"fieldKey": "name", "fieldName": "姓名"},
        {"fieldKey": "gender", "fieldName": "性别"},
        {"fieldKey": "nation", "fieldName": "民族"},
        {"fieldKey": "birthDate", "fieldName": "出生日期"},
        {"fieldKey": "address", "fieldName": "住址"},
        {"fieldKey": "idNumber", "fieldName": "身份证号"},
        {"fieldKey": "issuingAuthority", "fieldName": "签发机关"},
        {"fieldKey": "validPeriod", "fieldName": "有效期限"},
        {"fieldKey": "hasWatermark", "fieldName": "是否有水印"},
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
        prompt = self._build_prompt(request, ocr_type)
        raw, usage = await self.qwen.vision_json_chat(
            prompt,
            request.file.downloadUrl,
            request.file.contentType,
        )
        data = self._normalize_response(raw, ocr_type)
        return data, usage

    def _normalize_type(self, ocr_type: str) -> str:
        normalized = (ocr_type or "").upper()
        if normalized == "CONTRACT":
            return "CUSTOM"
        if normalized not in {"ID_CARD", "LICENSE_PLATE", "INVOICE", "CUSTOM"}:
            raise ValueError("unsupported ocrType")
        return normalized

    def _build_prompt(self, request: OcrRecognizeRequest, ocr_type: str) -> str:
        fields = self._field_definitions(request, ocr_type)
        return (
            "你是智慧工地OCR字段抽取服务。请识别上传的图片或PDF文件，并严格返回JSON对象，不要返回Markdown。\n"
            f"OCR类型: {ocr_type}\n"
            f"文件名: {request.file.fileName}\n"
            f"内容类型: {request.file.contentType or 'unknown'}\n"
            f"额外选项: {request.options}\n"
            f"需要抽取的字段定义: {fields}\n"
            "只允许输出一个紧凑JSON对象，不要输出Markdown、注释或解释。所有字符串必须使用英文双引号，字符串内部双引号必须转义，evidence不要超过80个中文字符。\n"
            "输出JSON格式必须为: {"
            "\"ocrType\":\"...\","
            "\"confidence\":0到1之间数字,"
            "\"fields\":[{\"fieldKey\":\"...\",\"fieldName\":\"...\",\"fieldValue\":\"...\",\"confidence\":0到1之间数字,\"location\":\"页码或区域\",\"pageNo\":1,\"evidence\":\"原文证据\"}],"
            "\"extras\":{},"
            "\"raw\":{}"
            "}。\n"
            "如果字段不可见或无法确认，fieldValue返回空字符串，confidence返回0到0.3之间，不要编造。"
            "身份证水印需要在extras.watermark中返回detected、type、text、confidence。"
            "车牌需要在extras.plate中返回number、backgroundColor、fontColor、plateType、bbox。"
            "发票需要在extras.items中返回明细行，并在extras.validation中返回金额校验结果。"
            "自定义合同字段需要尽量返回evidence和pageNo。"
        )

    def _field_definitions(self, request: OcrRecognizeRequest, ocr_type: str) -> list[dict[str, Any]]:
        if ocr_type == "CUSTOM":
            fields = request.options.get("customFields") or []
            if not isinstance(fields, list) or not fields:
                raise ValueError("customFields is required for CUSTOM OCR")
            return fields
        return STANDARD_FIELDS[ocr_type]

    def _normalize_response(self, raw: dict[str, Any], ocr_type: str) -> OcrRecognizeData:
        fields = raw.get("fields") or []
        if not isinstance(fields, list):
            fields = []
        normalized_fields: list[OcrFieldData] = []
        for item in fields:
            if not isinstance(item, dict):
                continue
            normalized_fields.append(OcrFieldData(
                fieldKey=str(item.get("fieldKey") or item.get("key") or ""),
                fieldName=str(item.get("fieldName") or item.get("name") or ""),
                fieldValue="" if item.get("fieldValue") is None else str(item.get("fieldValue")),
                confidence=self._confidence(item.get("confidence")),
                location=optional_string(item.get("location")),
                pageNo=optional_int(item.get("pageNo")),
                evidence=optional_string(item.get("evidence")),
            ))
        return OcrRecognizeData(
            ocrType=str(raw.get("ocrType") or ocr_type),
            confidence=self._confidence(raw.get("confidence")),
            fields=normalized_fields,
            extras=as_dict(raw.get("extras")),
            raw=raw.get("raw") if isinstance(raw.get("raw"), dict) else {"providerJson": raw},
        )

    def _confidence(self, value: Any) -> float:
        try:
            number = float(value)
        except (TypeError, ValueError):
            return 0
        return max(0, min(1, number))
