import { describe, expect, it } from 'vitest';
import { fileParseStatusText, hasActiveFileParse, isRetryableFileParse } from './fileParseStatus';

describe('fileParseStatusText', () => {
  it.each([
    ['PENDING', '待处理'],
    ['RUNNING', '解析中'],
    ['SUCCESS', '解析成功'],
    ['FAILED', '解析失败']
  ])('maps %s to %s', (status, expected) => {
    expect(fileParseStatusText(status)).toBe(expected);
  });

  it('normalizes lowercase status and preserves unknown values', () => {
    expect(fileParseStatusText('running')).toBe('解析中');
    expect(fileParseStatusText('CUSTOM')).toBe('CUSTOM');
  });
});

describe('hasActiveFileParse', () => {
  it('returns true while any record is pending or parsing', () => {
    expect(hasActiveFileParse([{ status: 'SUCCESS' }, { status: 'RUNNING' }])).toBe(true);
    expect(hasActiveFileParse([{ status: 'PENDING' }])).toBe(true);
    expect(hasActiveFileParse([{ status: 'PARSING' }])).toBe(true);
  });

  it('returns false after all records reach terminal states', () => {
    expect(hasActiveFileParse([{ status: 'PARSED' }, { status: 'FAILED' }])).toBe(false);
    expect(hasActiveFileParse([])).toBe(false);
  });
});


describe('isRetryableFileParse', () => {
  it.each(['FAILED', 'CANCELED', 'failed', 'canceled'])('allows retrying %s records', (status) => {
    expect(isRetryableFileParse({ status })).toBe(true);
  });

  it.each(['PENDING', 'PARSING', 'RUNNING', 'PARSED', 'SUCCESS'])('rejects retrying %s records', (status) => {
    expect(isRetryableFileParse({ status })).toBe(false);
  });
});
