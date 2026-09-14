import { describe, expect, it } from 'vitest';
import { applyOcrCandidate, canConfirmOcrRecord, confidenceLabel, documentTypeNotice, fieldConfirmationReasonLabel, fieldLocationLabel, fieldReviewLabel, invoiceItems, invoiceValidation, ocrRuntimeMeta } from './ocrDetail';

describe('OCR detail metadata', () => {
  it('reads provider and semantic model metadata from the persisted summary', () => {
    expect(ocrRuntimeMeta({ rawResult: { summary: {
      provider: 'PADDLE_OCRV5', model: 'PP-OCRv5', semanticProvider: 'QWEN_VL', semanticModel: 'smart-worksite-chat'
    } } } as any)).toEqual({
      provider: 'PADDLE_OCRV5', model: 'PP-OCRv5', semanticProvider: 'QWEN_VL', semanticModel: 'smart-worksite-chat'
    });
  });

  it('formats confidence and location without inventing missing evidence', () => {
    expect(confidenceLabel(0.987)).toBe('98.7%');
    expect(confidenceLabel(null)).toBe('未提供');
    expect(fieldLocationLabel({ location: '', pageNo: 3, fieldName: '姓名', fieldValue: '', confidence: 0 })).toBe('第3页');
  });

  it('prioritizes manual confirmation over the revised marker', () => {
    expect(fieldReviewLabel({ manualConfirmationRequired: true, revised: true } as any)).toBe('需人工确认');
    expect(fieldReviewLabel({ revised: true } as any)).toBe('已修订');
  });

  it('explains stable confirmation reason codes in Chinese and keeps old records readable', () => {
    expect(fieldConfirmationReasonLabel({ confirmationReason: 'MISSING_SIDE_OR_PAGE' } as any)).toBe('缺少证件背面或必要页面');
    expect(fieldConfirmationReasonLabel({ confirmationReason: 'SOURCE_MASKED' } as any)).toBe('原图内容被遮挡或打码');
    expect(fieldConfirmationReasonLabel({ confirmationReason: 'TYPE_MISMATCH' } as any)).toBe('所选识别类型与图片内容不一致');
    expect(fieldConfirmationReasonLabel({ confirmationReason: 'DUAL_PASS_CONFLICT' } as any)).toBe('两路识别结果冲突，请从候选值中确认');
    expect(fieldConfirmationReasonLabel({ confirmationReason: 'LOW_CONFIDENCE' } as any)).toBe('识别置信度较低');
    expect(fieldConfirmationReasonLabel({ confirmationReason: 'FIELD_NOT_VISIBLE' } as any)).toBe('图片中未见该字段');
    expect(fieldConfirmationReasonLabel({ manualConfirmationRequired: true } as any)).toBe('需要人工确认');
  });

  it('shows type mismatch only for a confident constrained classification', () => {
    expect(documentTypeNotice({ rawResult: { extras: { documentType: {
      selectedType: 'PASSPORT', detectedType: 'FIVE_STAR_CARD', confidence: 0.96, mismatch: true
    } } } } as any)).toBe('所选类型“护照”与检测类型“五星卡”不一致（96.0%）');
    expect(documentTypeNotice({ rawResult: { extras: { documentType: {
      selectedType: 'PASSPORT', detectedType: 'UNKNOWN', confidence: 0.2, mismatch: false
    } } } } as any)).toBe('文档类型无法可靠判断，请人工确认');
    expect(documentTypeNotice({ rawResult: {} } as any)).toBe('');
  });

  it('turns a selected conflict candidate into an explicit human revision', () => {
    const field = { fieldValue: '', confidence: 0, manualConfirmationRequired: true, confirmationReason: 'DUAL_PASS_CONFLICT', candidates: [{ value: '冼海华', confidence: 0.93, evidence: '姓名 冼海华' }] } as any;

    applyOcrCandidate(field, field.candidates[0]);

    expect(field).toMatchObject({ fieldValue: '冼海华', confidence: 0.93, evidence: '姓名 冼海华', manualConfirmationRequired: false, confirmationReason: undefined, candidates: [], revised: true });
  });

  it('allows confirmation only for an unconfirmed terminal result', () => {
    expect(canConfirmOcrRecord({ status: 'SUCCESS', manuallyConfirmed: false } as any)).toBe(true);
    expect(canConfirmOcrRecord({ status: 'PARTIAL_SUCCESS', manuallyConfirmed: false } as any)).toBe(true);
    expect(canConfirmOcrRecord({ status: 'PARTIAL_SUCCESS', manuallyConfirmed: false, fields: [{ manualConfirmationRequired: true }] } as any)).toBe(false);
    expect(canConfirmOcrRecord({ status: 'PROCESSING', manuallyConfirmed: false } as any)).toBe(false);
    expect(canConfirmOcrRecord({ status: 'SUCCESS', manuallyConfirmed: true } as any)).toBe(false);
  });

  it('reads invoice detail rows and validation without trusting malformed extras', () => {
    const record = { ocrType: 'INVOICE', rawResult: { extras: {
      items: [{ name: '汽油92号', quantity: '33.15', amount: '207.70', amountConsistent: true }, 'bad-row'],
      validation: { itemAmountsConsistent: true, itemTaxConsistent: false, itemsTruncated: false }
    } } } as any;

    expect(invoiceItems(record)).toEqual([{ name: '汽油92号', quantity: '33.15', amount: '207.70', amountConsistent: true }]);
    expect(invoiceValidation(record)).toEqual({ itemAmountsConsistent: true, itemTaxConsistent: false, itemsTruncated: false });
    expect(invoiceItems({ rawResult: { extras: { items: 'invalid' } } } as any)).toEqual([]);
  });
});
