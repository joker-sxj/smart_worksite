import { describe, expect, it } from 'vitest';
import { fileParseStatusText, hasActiveFileParse } from './fileParseStatus';

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
  it('returns true while any record is pending or running', () => {
    expect(hasActiveFileParse([{ status: 'SUCCESS' }, { status: 'RUNNING' }])).toBe(true);
    expect(hasActiveFileParse([{ status: 'PENDING' }])).toBe(true);
  });

  it('returns false after all records reach terminal states', () => {
    expect(hasActiveFileParse([{ status: 'SUCCESS' }, { status: 'FAILED' }])).toBe(false);
    expect(hasActiveFileParse([])).toBe(false);
  });
});
