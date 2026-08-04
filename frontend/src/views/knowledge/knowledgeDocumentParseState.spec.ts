import { describe, expect, it } from 'vitest';
import type { FileParseRecord } from '../../api/file';
import {
  documentParseActionText,
  documentParseRecord,
  hasActiveDocumentParses,
  setDocumentParseRecord
} from './knowledgeDocumentParseState';

function record(status: string, recordId = 1): FileParseRecord {
  return { recordId, projectId: 1, fileId: 2, status };
}

describe('knowledge document parse state', () => {
  it('stores and retrieves the latest parse record by document id', () => {
    const records = setDocumentParseRecord({}, 10, record('PENDING'));

    expect(documentParseRecord(records, 10)?.status).toBe('PENDING');
    expect(documentParseRecord(records, '10')?.status).toBe('PENDING');
  });

  it('reports whether any document parse is still active', () => {
    expect(hasActiveDocumentParses({ a: record('RUNNING') })).toBe(true);
    expect(hasActiveDocumentParses({ a: record('PENDING') })).toBe(true);
    expect(hasActiveDocumentParses({ a: record('SUCCESS'), b: record('FAILED', 2) })).toBe(false);
  });

  it.each([
    [undefined, '解析文件'],
    ['PENDING', '解析中'],
    ['RUNNING', '解析中'],
    ['SUCCESS', '重新解析'],
    ['FAILED', '重新解析']
  ])('uses action text %s -> %s', (status, expected) => {
    expect(documentParseActionText(status ? record(status) : undefined)).toBe(expected);
  });
});
