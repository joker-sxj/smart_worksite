import { describe, expect, it } from 'vitest';
import { templateAccept, templateFileError, templateTip } from './templateUploadPolicy';

describe('template upload policy', () => {
  it('explains why PDF cannot be used as a report template', () => {
    const file = new File(['%PDF-1.7'], 'report.pdf', { type: 'application/pdf' });

    expect(templateAccept('REPORT')).toBe('.docx');
    expect(templateFileError('REPORT', file)).toContain('PDF不能作为报告模板输入');
    expect(templateFileError('REPORT', file)).toContain('DOCX');
    expect(templateTip('REPORT')).toContain('最终报告仍可导出 Word 和 PDF');
  });

  it('keeps PDF available for review templates', () => {
    const file = new File(['%PDF-1.7'], 'review.pdf', { type: 'application/pdf' });

    expect(templateAccept('REVIEW')).toContain('.pdf');
    expect(templateFileError('REVIEW', file)).toBe('');
  });
});
