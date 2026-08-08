import base64
import binascii
import json
import mimetypes
import re
from typing import Any
from urllib.parse import urlparse

import httpx
from app.core.settings import Settings
from app.models.schemas import Message


class QwenClient:
    def __init__(self, settings: Settings):
        self.settings = settings

    async def chat(self, messages: list[Message], model: str | None = None, parameters: dict[str, Any] | None = None) -> tuple[str, dict[str, Any]]:
        if not self.settings.qwen_api_key:
            raise RuntimeError("QWEN_API_KEY is not configured")
        payload: dict[str, Any] = {
            "model": model or self.settings.qwen_model,
            "messages": [{"role": item.role, "content": item.content} for item in messages],
        }
        if parameters:
            payload.update(parameters)
        url = self.settings.qwen_base_url.rstrip("/") + "/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.settings.qwen_api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        async with httpx.AsyncClient(timeout=self.settings.qwen_timeout_seconds) as client:
            response = await client.post(url, headers=headers, json=payload)
            response.raise_for_status()
            body = response.json()
        answer = body.get("choices", [{}])[0].get("message", {}).get("content", "")
        if not answer:
            raise RuntimeError("Qwen response content is empty")
        usage = body.get("usage") or {}
        return answer, usage

    async def json_chat(self, messages: list[Message], model: str | None = None, parameters: dict[str, Any] | None = None) -> tuple[dict[str, Any], dict[str, Any]]:
        answer, usage = await self.chat(messages, model, parameters)
        text = answer.strip()
        if text.startswith("```"):
            text = text.strip("`")
            if text.lower().startswith("json"):
                text = text[4:].strip()
        data = json.loads(text)
        if not isinstance(data, dict):
            raise ValueError("Qwen JSON response must be an object")
        return data, usage

    async def vision_json_chat(self, prompt: str, file_sources: str | list[str], content_type: str | None = None) -> tuple[dict[str, Any], dict[str, Any]]:
        api_key = self.settings.qwen_vl_api_key or self.settings.qwen_api_key
        if not api_key:
            raise RuntimeError("QWEN_VL_API_KEY is not configured")
        content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        normalized_type = self._normalize_mime_type(content_type)
        sources = [file_sources] if isinstance(file_sources, str) else file_sources
        if not sources:
            raise RuntimeError("OCR file source is empty")
        for file_url in sources:
            if normalized_type == "application/pdf" and not file_url.startswith("data:image/"):
                content.append({"type": "file_url", "file_url": {"url": file_url}})
            else:
                image_data_url = await self._download_image_as_data_url(file_url, normalized_type)
                content.append({"type": "image_url", "image_url": {"url": image_data_url}})
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        body = await self._post_qwen_vl(content, headers)
        try:
            return self._parse_vision_json_body(body)
        except json.JSONDecodeError:
            # Repair the exact OCR result without images first. Re-running vision tends to
            # reproduce the same quoting/comma mistakes and costs another full OCR pass.
            repair_content = [{
                "type": "text",
                "text": self._build_json_repair_prompt(self._extract_message_content(body)),
            }]
            repair_body = await self._post_qwen_vl(repair_content, headers)
            try:
                return self._parse_vision_json_body(repair_body)
            except json.JSONDecodeError:
                retry_content = self._replace_prompt(content, self._build_json_retry_prompt(prompt))
                retry_body = await self._post_qwen_vl(retry_content, headers)
                try:
                    return self._parse_vision_json_body(retry_body)
                except json.JSONDecodeError as retry_ex:
                    raise RuntimeError(self._format_json_parse_error(retry_body, retry_ex)) from retry_ex

    async def _post_qwen_vl(self, content: list[dict[str, Any]], headers: dict[str, str]) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": self.settings.qwen_vl_model,
            "messages": [{"role": "user", "content": content}],
            "response_format": {"type": "json_object"},
            "presence_penalty": 1.5,
        }
        if self.settings.qwen_vl_max_tokens > 0:
            payload["max_tokens"] = self.settings.qwen_vl_max_tokens
        async with httpx.AsyncClient(timeout=self.settings.qwen_vl_timeout_seconds) as client:
            try:
                response = await client.post(self.settings.qwen_vl_endpoint, headers=headers, json=payload)
                response.raise_for_status()
            except httpx.HTTPStatusError as ex:
                raise RuntimeError(self._format_http_error("Qwen VL call failed", ex)) from ex
            return response.json()

    def _parse_vision_json_body(self, body: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        answer = self._extract_message_content(body)
        if not answer:
            raise RuntimeError("Qwen VL response content is empty")
        data = self._parse_json_object(answer)
        usage = body.get("usage") or {}
        usage["model"] = self.settings.qwen_vl_model
        usage["provider"] = "QWEN_VL"
        finish_reason = self._finish_reason(body)
        if finish_reason:
            usage["finishReason"] = finish_reason
        return data, usage

    async def _download_image_as_data_url(self, file_url: str, content_type: str | None) -> str:
        if file_url.startswith("data:image/"):
            self._validate_image_data_url(file_url)
            return file_url

        async with httpx.AsyncClient(
            timeout=self.settings.qwen_vl_timeout_seconds,
            follow_redirects=True,
            trust_env=False,
        ) as client:
            try:
                response = await client.get(file_url)
                response.raise_for_status()
            except httpx.HTTPStatusError as ex:
                raise RuntimeError(self._format_http_error("OCR file download failed", ex)) from ex
            except httpx.RequestError as ex:
                host = urlparse(file_url).hostname or "unknown"
                raise RuntimeError(
                    f"OCR file download failed (host={host}): {ex.__class__.__name__}"
                ) from ex

        data = response.content
        if len(data) > self.settings.qwen_vl_max_image_bytes:
            raise RuntimeError(
                "OCR image is too large for base64 Qwen VL request: "
                f"{len(data)} bytes > {self.settings.qwen_vl_max_image_bytes} bytes"
            )

        mime_type = self._resolve_image_mime_type(
            content_type,
            response.headers.get("content-type"),
            file_url,
        )
        if not mime_type.startswith("image/"):
            raise RuntimeError(f"OCR image input requires image content, got {mime_type or 'unknown'}")

        encoded = base64.b64encode(data).decode("ascii")
        return f"data:{mime_type};base64,{encoded}"

    def _validate_image_data_url(self, data_url: str) -> None:
        try:
            header, encoded = data_url.split(",", 1)
            if ";base64" not in header:
                raise ValueError("not base64 encoded")
            data = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as ex:
            raise RuntimeError("OCR inline image data URL is invalid") from ex
        if len(data) > self.settings.qwen_vl_max_image_bytes:
            raise RuntimeError(
                "OCR image is too large for base64 Qwen VL request: "
                f"{len(data)} bytes > {self.settings.qwen_vl_max_image_bytes} bytes"
            )

    def _resolve_image_mime_type(self, *candidates: str | None) -> str:
        fallback = ""
        for item in candidates[:-1]:
            mime_type = self._normalize_mime_type(item)
            if mime_type.startswith("image/"):
                return mime_type
            if mime_type and mime_type not in {"application/octet-stream", "binary/octet-stream"} and not fallback:
                fallback = mime_type

        file_url = candidates[-1] or ""
        guessed, _ = mimetypes.guess_type(urlparse(file_url).path)
        guessed_type = self._normalize_mime_type(guessed)
        if guessed_type:
            return guessed_type
        return fallback or "image/jpeg"

    def _normalize_mime_type(self, value: str | None) -> str:
        if not value:
            return ""
        mime_type = value.split(";", 1)[0].strip().lower()
        return "image/jpeg" if mime_type == "image/jpg" else mime_type

    def _format_http_error(self, prefix: str, ex: httpx.HTTPStatusError) -> str:
        body = ex.response.text.strip()
        if len(body) > 1000:
            body = body[:1000]
        if body:
            return f"{prefix}: HTTP {ex.response.status_code}: {body}"
        return f"{prefix}: HTTP {ex.response.status_code}"

    def _parse_json_object(self, answer: str) -> dict[str, Any]:
        text = self._strip_json_markdown(answer)
        candidates = [text]
        extracted = self._extract_json_object_text(text)
        if extracted and extracted != text:
            candidates.append(extracted)

        last_error: json.JSONDecodeError | None = None
        for candidate in candidates:
            completed = self._complete_json_delimiters(candidate)
            variants = (
                candidate,
                self._remove_trailing_commas(candidate),
                completed,
                self._remove_trailing_commas(completed),
            )
            for item in variants:
                try:
                    value = json.loads(item)
                except json.JSONDecodeError as ex:
                    last_error = ex
                    continue
                if isinstance(value, dict):
                    return value
                raise RuntimeError("Qwen VL response JSON root must be an object")

        if last_error:
            raise last_error
        raise RuntimeError("Qwen VL response content is not a JSON object")

    def _strip_json_markdown(self, answer: str) -> str:
        text = answer.strip()
        if text.startswith("```"):
            text = text.strip("`").strip()
            if text.lower().startswith("json"):
                text = text[4:].strip()
        return text

    def _extract_json_object_text(self, text: str) -> str:
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            return text[start:end + 1]
        return ""

    def _complete_json_delimiters(self, text: str) -> str:
        stack: list[str] = []
        in_string = False
        escaped = False
        for char in text:
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "{":
                stack.append("}")
            elif char == "[":
                stack.append("]")
            elif char in "}]":
                if not stack or stack[-1] != char:
                    return text
                stack.pop()
        if in_string or not stack:
            return text
        return text.rstrip() + "".join(reversed(stack))

    def _remove_trailing_commas(self, text: str) -> str:
        return re.sub(r",\s*([}\]])", r"\1", text)

    def _build_json_repair_prompt(self, invalid_json: str) -> str:
        return (
            "你是JSON语法修复器。只修复JSON语法，不要重新识别、概括、删减或改写任何字段和值。"
            "补齐缺失的逗号、引号、括号和必要转义，并只输出一个完整的紧凑JSON对象；"
            "不要返回Markdown、注释或解释。待修复内容如下：\n"
            + invalid_json
        )

    def _build_json_retry_prompt(self, prompt: str) -> str:
        return (
            prompt
            + "\n\n重要：上一次输出不是合法JSON。请重新识别，并只输出一个完整JSON对象。"
            "不要返回Markdown，不要解释。所有字符串必须使用英文双引号，字符串内部双引号必须转义。"
            "尽量使用紧凑JSON，evidence字段控制在80个中文字符以内。"
            "如果字段不可见或无法确认，fieldValue返回空字符串，confidence返回0到0.3之间。"
        )

    def _replace_prompt(self, content: list[dict[str, Any]], prompt: str) -> list[dict[str, Any]]:
        next_content = [dict(item) for item in content]
        for item in next_content:
            if item.get("type") == "text":
                item["text"] = prompt
                return next_content
        return [{"type": "text", "text": prompt}, *next_content]

    def _format_json_parse_error(self, body: dict[str, Any], ex: json.JSONDecodeError) -> str:
        answer = self._extract_message_content(body)
        finish_reason = self._finish_reason(body) or "unknown"
        reason = "Qwen VL response was truncated" if finish_reason == "length" else "Qwen VL returned invalid JSON"
        return (
            f"{reason}: finishReason={finish_reason}, contentLength={len(answer)}, "
            f"parseError={ex.msg} at line {ex.lineno} column {ex.colno}. "
            "Try increasing QWEN_VL_MAX_TOKENS or reducing requested OCR fields."
        )

    def _finish_reason(self, body: dict[str, Any]) -> str:
        choices = body.get("choices") or []
        if not choices or not isinstance(choices[0], dict):
            return ""
        return str(choices[0].get("finish_reason") or choices[0].get("finishReason") or "")

    def _extract_message_content(self, body: dict[str, Any]) -> str:
        content = body.get("choices", [{}])[0].get("message", {}).get("content", "")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts: list[str] = []
            for item in content:
                if isinstance(item, dict) and item.get("text"):
                    parts.append(str(item.get("text")))
                elif isinstance(item, str):
                    parts.append(item)
            return "".join(parts)
        return str(content or "")

    async def embed(self, texts: list[str], model: str | None = None) -> tuple[list[list[float]], dict[str, Any]]:
        if not texts:
            return [], {}
        if not self.settings.qwen_api_key:
            raise RuntimeError("QWEN_API_KEY is not configured")
        payload: dict[str, Any] = {
            "model": model or self.settings.qwen_embedding_model,
            "input": texts,
        }
        if self.settings.qwen_embedding_dimensions > 0:
            payload["dimensions"] = self.settings.qwen_embedding_dimensions
        payload["encoding_format"] = "float"
        url = self.settings.qwen_base_url.rstrip("/") + "/embeddings"
        headers = {
            "Authorization": f"Bearer {self.settings.qwen_api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        async with httpx.AsyncClient(timeout=self.settings.qwen_timeout_seconds) as client:
            try:
                response = await client.post(url, headers=headers, json=payload)
                response.raise_for_status()
            except httpx.HTTPStatusError as ex:
                if ex.response.status_code == 400 and "dimensions" in payload:
                    retry_payload = dict(payload)
                    retry_payload.pop("dimensions", None)
                    try:
                        response = await client.post(url, headers=headers, json=retry_payload)
                        response.raise_for_status()
                    except httpx.HTTPStatusError as retry_ex:
                        raise RuntimeError(self._format_embedding_http_error(retry_ex, retry_payload, len(texts))) from retry_ex
                else:
                    raise RuntimeError(self._format_embedding_http_error(ex, payload, len(texts))) from ex
            body = response.json()
        vectors = [
            item["embedding"]
            for item in sorted(body.get("data", []), key=lambda item: item.get("index", 0))
        ]
        return vectors, body.get("usage") or {}

    def _format_embedding_http_error(self, ex: httpx.HTTPStatusError, payload: dict[str, Any], input_count: int) -> str:
        model = payload.get("model") or "unknown"
        dimensions = payload.get("dimensions", "omitted")
        prefix = (
            "Qwen embedding call failed "
            f"(model={model}, inputCount={input_count}, dimensions={dimensions})"
        )
        return self._format_http_error(prefix, ex)

    async def rerank(self, query: str, documents: list[str], top_n: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        if not documents:
            return [], {}
        if not self.settings.qwen_api_key:
            raise RuntimeError("QWEN_API_KEY is not configured")
        url_base = (self.settings.qwen_rerank_base_url or self.settings.qwen_base_url).rstrip("/")
        payload = self._build_rerank_payload(query, documents, top_n)
        url = url_base if "/services/rerank/" in url_base else url_base + "/rerank"
        headers = {
            "Authorization": f"Bearer {self.settings.qwen_api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        async with httpx.AsyncClient(timeout=self.settings.qwen_timeout_seconds) as client:
            response = await client.post(url, headers=headers, json=payload)
            response.raise_for_status()
            body = response.json()
        output = body.get("output") or {}
        results = output.get("results") or body.get("results") or []
        return results, body.get("usage") or {}

    def _build_rerank_payload(self, query: str, documents: list[str], top_n: int) -> dict[str, Any]:
        model = self.settings.qwen_rerank_model
        api_style = self.settings.qwen_rerank_api_style.upper()
        if api_style == "QWEN3" or (api_style == "AUTO" and model == "qwen3-rerank"):
            return {
                "model": model,
                "query": query,
                "documents": documents,
                "top_n": top_n,
                "return_documents": False,
                "instruction": self.settings.qwen_rerank_instruct,
            }
        return {
            "model": model,
            "input": {
                "query": query,
                "documents": documents,
            },
            "parameters": {
                "return_documents": False,
                "top_n": top_n,
                "instruction": self.settings.qwen_rerank_instruct,
            },
        }
