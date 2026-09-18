import { describe, expect, it, vi } from 'vitest';
import type { KnowledgeDocument } from './types';

vi.mock('../utils/request', () => ({ default: {} }));
vi.mock('./mock', () => ({ useModuleMock: () => false }));

import { buildMockKnowledgeDocumentPage } from './knowledge';

function document(documentId: number, knowledgeBaseId: number, title: string, indexStatus: KnowledgeDocument['indexStatus']): KnowledgeDocument {
  return {
    documentId,
    projectId: 1,
    knowledgeBaseId,
    fileId: documentId,
    title,
    sourceType: 'UPLOAD',
    indexStatus,
    createdAt: '2026-09-18T00:00:00Z',
    updatedAt: '2026-09-18T00:00:00Z'
  };
}

describe('knowledge document mock pagination', () => {
  const records = [
    document(1, 10, '安全方案 A', 'SUCCESS'),
    document(2, 10, '质量方案', 'FAILED'),
    document(3, 10, '安全方案 B', 'SUCCESS'),
    document(4, 11, '安全方案 C', 'SUCCESS')
  ];

  it('applies base, keyword and status filters before pagination', () => {
    expect(buildMockKnowledgeDocumentPage(records, 10, {
      pageNo: 2,
      pageSize: 1,
      keyword: '  安全  ',
      indexStatus: 'SUCCESS'
    })).toEqual({ pageNo: 2, pageSize: 1, total: 2, records: [records[2]] });
  });
});
