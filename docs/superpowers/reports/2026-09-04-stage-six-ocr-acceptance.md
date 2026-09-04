# 第六阶段 OCR 实机测试验收文档

日期：2026-09-04
版本：18b769d4697dcaa80eaf22ce88f0bfa1d7fa2038
远程仓库：joker-sxj/smart_worksite:main
服务器：xidian@172.18.12.6:/home/xidian/sjw/smart_worksite
模型配置：h100-fp8（服务器为双 H100；不作为客户双 A6000 性能承诺）

## 验收结论

本阶段已完成 OCR 生产闭环的五个功能点，并完成 54 个可重复验证场景：54 通过，0 失败。测试使用本地模型链路，未启用云端回退。Java、Python、前端构建和全量自动化测试均通过。

## 功能范围

- OCR 状态：PENDING、PROCESSING、SUCCESS、PARTIAL_SUCCESS、FAILED；空字段不会伪装成成功。
- 字段治理：字段级置信度、页码/位置/证据、人工确认标志、修订历史和敏感字段脱敏。
- 身份证：原图保留、方向/对比度/锐度预处理、双路识别，冲突字段进入人工确认。
- 车牌：蓝牌和新能源绿牌识别、分隔符归一化、车牌结构校验。
- 发票：专票类型校验、不含税金额 + 税额 = 价税合计校验。
- 合同/自定义字段：字段编码、名称、类型、长度、数量、重复项和敏感字段校验；前端支持新增、删除、类型、必填、敏感和说明配置。

## 真实实机样本

- /tmp/plate-blue.png：本地生成的非个人蓝牌测试图，期望粤B12345。
- /tmp/plate-green.png：本地生成的非个人新能源测试图，期望粤BD12345。
- /tmp/invoice-special-test.png：本地生成的非真实发票样张，金额 1000.00 + 130.00 = 1130.00。
- /tmp/contract-custom-test.png：本地生成的非真实合同样张，含合同编号、甲方、金额、付款条件。
- /tmp/ocr-test.pdf：此前上传的真实 PDF OCR 负向/不完整场景样本。

上述样本不含真实个人身份信息、真实发票或有效合同；准确率结论仅适用于本次功能验收，不等同于客户双 A6000 的性能或行业准确率承诺。

## 实机记录

- 记录 27：身份证不完整，状态 PARTIAL_SUCCESS，进度 100，所有空字段 manualConfirmationRequired=true。
- 记录 28：蓝牌识别成功，粤B12345，结构校验通过。
- 记录 29：新能源绿牌识别成功，粤BD12345，结构校验通过。
- 记录 31：专票识别成功，金额校验通过，发票类型一致。
- 记录 32：自定义合同识别成功，4 个字段均含 pageNo/evidence。
- Chrome OCR 页面已展示记录 28-32；切换到“自定义字段识别”后已显示结构化字段编辑器；该页面本轮控制台无 warn/error。

## 54 场景结果

执行脚本：服务器 /tmp/stage6-ocr-acceptance.sh。最终结果：PASS=54 FAIL=0。

覆盖内容：Java/Python 健康、本地模式、四类模型就绪、OCR 类型模板、未登录/不存在记录/非法类型/缺失参数/重复字段/危险编码/非法类型/长度和数量限制、蓝牌、新能源绿牌、发票三金额、合同字段、下载、分页、类型筛选、部分成功、人工确认、敏感原始结果控制、Redis/MySQL/MinIO/Python/本地 LLM 容器健康。

## 自动化验证

- Java：mvn -q test，382 tests，0 failures，0 errors，0 skipped。
- Python：py -m pytest -q，375 passed，2 个既有 Pydantic protected namespace warnings。
- Python：py -m compileall -q app tests，通过。
- Frontend：12 test files，97 passed；生产构建通过。
- git diff --check：通过。

Java 测试中的 ERROR/WARN 是故障模拟测试主动输出，不是本轮生产日志异常。

## 日志和运行状态

- Java actuator：UP。
- Python AI：UP，deploymentMode=LOCAL_ONLY，chat/vision/embedding/rerank 均 READY。
- 本轮 Python OCR 日志：无 error/exception/traceback/failed。
- 本轮 Java OCR 日志：无 OCR error/exception；PDFBox 字体 fallback 警告仅来自 PDF 渲染字体，不影响本轮图片 OCR。
- Redis、MySQL、MinIO、Python AI、本地 LLM：healthy。

## 已知限制

- 服务器当前为双 H100，未在双 A6000 上做性能测试；客户交付需用 A6000 profile 重新压测上下文长度、吞吐和并发。
- Chrome 扩展的文件选择器曾出现超时，导致本轮文件上传动作采用后端真实上传 API 完成，Chrome 负责页面记录、状态和字段配置实机验证；需要在客户 Chrome 开启扩展“Allow access to file URLs”后补做一次纯浏览器上传回归。
- 样本是脱敏/合成验收样本，没有客户标注集，因此不宣称 95% 准确率。

## 后续

建议客户提供获得授权的身份证水印、车牌、发票和合同标注集，在双 A6000 profile 上复测；补测通过后再形成客户环境准确率与性能报告。
