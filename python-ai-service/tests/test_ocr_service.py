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
