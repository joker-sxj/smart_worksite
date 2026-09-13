import { describe, expect, it } from 'vitest';
import { confidenceLabel, fieldLocationLabel, fieldReviewLabel, ocrRuntimeMeta } from './ocrDetail';

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
});
