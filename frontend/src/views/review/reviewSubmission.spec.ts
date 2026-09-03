import { describe, expect, it } from 'vitest';
import { buildReviewFormData } from './reviewSubmission';

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
});
