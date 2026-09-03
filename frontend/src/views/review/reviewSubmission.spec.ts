import { describe, expect, it } from 'vitest';
import { buildReviewFormData, exceedsReviewReferenceLimit } from './reviewSubmission';

describe('review submission', () => {
  it('serializes multiple knowledge documents and temporary reference files', () => {
    const main = new File(['main'], 'plan.pdf', { type: 'application/pdf' });
    const reference = new File(['rule'], 'standard.pdf', { type: 'application/pdf' });

    const form = buildReviewFormData({
      projectId: 1,
      templateId: 2,
      file: main,
      referenceDocumentIds: [7, 8],
      referenceFiles: [reference]
    });

    expect(form.getAll('referenceDocumentIds')).toEqual(['7', '8']);
    expect(form.getAll('referenceFiles')).toEqual([reference]);
    expect(form.get('file')).toBe(main);
  });

  it('rejects a combined reference selection above the backend limit', () => {
    expect(exceedsReviewReferenceLimit(
      Array.from({ length: 11 }, (_, index) => index + 1),
      new Array(10).fill(null)
    )).toBe(true);

    expect(exceedsReviewReferenceLimit(
      Array.from({ length: 10 }, (_, index) => index + 1),
      new Array(10).fill(null)
    )).toBe(false);
  });
});
