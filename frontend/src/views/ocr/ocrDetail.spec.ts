import { describe, expect, it } from 'vitest';
import { canConfirmOcrRecord, confidenceLabel, fieldLocationLabel, fieldReviewLabel, invoiceItems, invoiceValidation, ocrRuntimeMeta } from './ocrDetail';

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
