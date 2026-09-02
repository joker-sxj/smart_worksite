import { describe, expect, it } from 'vitest';
import type { FileParseRecord } from '../../api/file';
import type { KnowledgeDocument } from '../../api/types';
import {
  documentParseActionText,
  documentProcessingMessage,
  documentParseRecord,
  hasActiveDocumentParses,
  isDocumentParseReady,
  isKnowledgeDocumentIndexReady,
  isParseableKnowledgeDocument,
  knowledgeDocumentParseTargetFormat,
  setDocumentParseRecord
} from './knowledgeDocumentParseState';

function record(status: string, recordId = 1): FileParseRecord {
  return { recordId, projectId: 1, fileId: 2, status };
}

describe('knowledge document parse state', () => {
  it.each(['PARSED', 'SUCCESS'])('treats %s as a reusable parse result', (status) => {
    expect(isDocumentParseReady(record(status))).toBe(true);
  });

  it.each(['PENDING', 'PARSING', 'RUNNING', 'FAILED', undefined])('does not treat %s as ready', (status) => {
    expect(isDocumentParseReady(status ? record(status) : undefined)).toBe(false);
  });

  it('allows indexing only after a successful parse and from a retryable index state', () => {
    expect(isKnowledgeDocumentIndexReady('PENDING', record('SUCCESS'))).toBe(true);
    expect(isKnowledgeDocumentIndexReady('FAILED', record('PARSED'))).toBe(true);
    expect(isKnowledgeDocumentIndexReady('PENDING', record('FAILED'))).toBe(false);
    expect(isKnowledgeDocumentIndexReady('SUCCESS', record('SUCCESS'))).toBe(false);
  });

  it('prefers the actionable parse error over the indexing error', () => {
    expect(documentProcessingMessage('入库失败', { ...record('FAILED'), errorMessage: '未发现可解析文本，需使用 OCR' }))
      .toBe('未发现可解析文本，需使用 OCR');
    expect(documentProcessingMessage('向量服务不可用', record('SUCCESS'))).toBe('向量服务不可用');
  });
  it('stores and retrieves the latest parse record by document id', () => {
    const records = setDocumentParseRecord({}, 10, record('PENDING'));

    expect(documentParseRecord(records, 10)?.status).toBe('PENDING');
    expect(documentParseRecord(records, '10')?.status).toBe('PENDING');
  });

  it('reports whether any document parse is still active', () => {
    expect(hasActiveDocumentParses({ a: record('RUNNING') })).toBe(true);
    expect(hasActiveDocumentParses({ a: record('PARSING') })).toBe(true);
    expect(hasActiveDocumentParses({ a: record('PENDING') })).toBe(true);
    expect(hasActiveDocumentParses({ a: record('SUCCESS'), b: record('FAILED', 2) })).toBe(false);
  });

  it.each([
    [undefined, '解析文件'],
    ['PENDING', '解析中'],
    ['PARSING', '解析中'],
    ['RUNNING', '解析中'],
    ['PARSED', '重新解析'],
    ['SUCCESS', '重新解析'],
    ['FAILED', '重新解析'],
    ['CANCELED', '重新解析']
  ])('uses action text %s -> %s', (status, expected) => {
    expect(documentParseActionText(status ? record(status) : undefined)).toBe(expected);
  });

  it('uses stored extension when the document title has no suffix', () => {
    const document: KnowledgeDocument = { documentId: 10, projectId: 1, knowledgeBaseId: 2, fileId: 3, title: '施工风险台账', fileExt: 'xlsx', contentType: 'application/octet-stream', indexStatus: 'PENDING', createdAt: '', updatedAt: '' };
    expect(isParseableKnowledgeDocument(document)).toBe(true);
    expect(knowledgeDocumentParseTargetFormat(document)).toBe('MARKDOWN');
  });

  it('uses stored content type when neither title nor extension identifies the presentation', () => {
    const document: KnowledgeDocument = { documentId: 11, projectId: 1, knowledgeBaseId: 2, fileId: 4, title: '施工汇报', contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', indexStatus: 'PENDING', createdAt: '', updatedAt: '' };
    expect(isParseableKnowledgeDocument(document)).toBe(true);
    expect(knowledgeDocumentParseTargetFormat(document)).toBe('MARKDOWN');
  });

});
