import { describe, expect, it } from 'vitest';
import { buildReviewFormData, validateReviewFieldValues } from './reviewSubmission';

describe('review field schema', () => {
  it('submits configured input values without using OCR custom fields', () => {
    const form = buildReviewFormData({
      projectId: 1,
      templateId: 2,
      file: new File(['main'], 'plan.pdf', { type: 'application/pdf' }),
      fieldValues: { project_code: 'P-001' },
      schemaVersion: 3
    });

    expect(form.get('schemaVersion')).toBe('3');
    expect(JSON.parse(String(form.get('fieldValues')))).toEqual({ project_code: 'P-001' });
  });

  it('reports missing required review fields using the schema rules', () => {
    expect(validateReviewFieldValues([
      { key: 'project_code', stage: 'INPUT', type: 'STRING', required: true, sort: 1, options: [], validation: {} }
    ], {})).toEqual(['project_code']);
  });
});
