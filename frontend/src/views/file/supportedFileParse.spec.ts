import { describe, expect, it } from 'vitest';
import { isParseableFileName, parseTargetFormatForFileName } from './supportedFileParse';

describe('supported file parsing', () => {
  it.each(['risk.xlsx', 'legacy.xls', 'risk.csv', 'progress.tsv', 'briefing.pptx', 'legacy.ppt'])('accepts %s', (fileName) => {
    expect(isParseableFileName(fileName)).toBe(true);
    expect(parseTargetFormatForFileName(fileName)).toBe('MARKDOWN');
  });

  it('keeps image parsing as text and rejects unrelated files', () => {
    expect(parseTargetFormatForFileName('gate.jpg')).toBe('TEXT');
    expect(isParseableFileName('archive.zip')).toBe(false);
  });
});
