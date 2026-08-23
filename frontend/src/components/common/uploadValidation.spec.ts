import { describe, expect, it } from 'vitest';
import { DEFAULT_UPLOAD_MAX_SIZE_MB, validateUploadFile } from './uploadValidation';

describe('upload validation', () => {
  it('accepts the 20,770 KiB acceptance PDF under the shared 100 MiB limit', () => {
    const file = { name: 'db11-1028-2013.pdf', size: 20_770 * 1024, type: 'application/pdf' } as File;

    expect(DEFAULT_UPLOAD_MAX_SIZE_MB).toBe(100);
    expect(validateUploadFile(file, DEFAULT_UPLOAD_MAX_SIZE_MB, '.pdf')).toBe('');
  });

  it('rejects a file over 100 MiB and reports measured and allowed sizes', () => {
    const file = { name: 'oversized.pdf', size: 100 * 1024 * 1024 + 512 * 1024, type: 'application/pdf' } as File;

    expect(validateUploadFile(file, 100, '.pdf')).toBe('文件 oversized.pdf 大小为 100.50MB，超过 100MB 限制');
  });
});
