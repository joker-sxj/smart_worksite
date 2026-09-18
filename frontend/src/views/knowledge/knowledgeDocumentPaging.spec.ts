import { describe, expect, it } from 'vitest';
import {
  buildKnowledgeDocumentQuery,
  createKnowledgeDocumentRequestGuard,
  isSameKnowledgeBase,
  knowledgeDocumentPageAfterDelete,
  resetKnowledgeDocumentPage,
  updateKnowledgeDocumentFilters,
  type KnowledgeDocumentPaging
} from './knowledgeDocumentPaging';

function paging(overrides: Partial<KnowledgeDocumentPaging> = {}): KnowledgeDocumentPaging {
  return {
    pageNo: 3,
    pageSize: 20,
    total: 65,
    keyword: '  安全方案  ',
    indexStatus: 'SUCCESS',
    ...overrides
  };
}

describe('knowledge document paging', () => {
  it('builds the server query from the current page and filters', () => {
    expect(buildKnowledgeDocumentQuery(paging())).toEqual({
      pageNo: 3,
      pageSize: 20,
      keyword: '安全方案',
      indexStatus: 'SUCCESS'
    });
  });

  it('omits blank optional filters from the server query', () => {
    expect(buildKnowledgeDocumentQuery(paging({ keyword: ' ', indexStatus: '' }))).toEqual({
      pageNo: 3,
      pageSize: 20
    });
  });

  it('returns to the first page when filters change', () => {
    expect(updateKnowledgeDocumentFilters(paging(), { keyword: '脚手架', indexStatus: 'FAILED' })).toMatchObject({
      pageNo: 1,
      pageSize: 20,
      keyword: '脚手架',
      indexStatus: 'FAILED'
    });
  });

  it('returns to the first page when switching bases or refreshing after upload', () => {
    expect(resetKnowledgeDocumentPage(paging())).toMatchObject({ pageNo: 1, keyword: '  安全方案  ', indexStatus: 'SUCCESS' });
  });

  it('moves back one page after deleting the only row on a later page', () => {
    expect(knowledgeDocumentPageAfterDelete(paging(), 1)).toBe(2);
  });

  it('keeps the current page when deleted page still has rows or is already first', () => {
    expect(knowledgeDocumentPageAfterDelete(paging(), 2)).toBe(3);
    expect(knowledgeDocumentPageAfterDelete(paging({ pageNo: 1 }), 1)).toBe(1);
  });

  it('keeps page and filters when building an automatic refresh query', () => {
    const state = paging({ pageNo: 4, keyword: '临边防护' });
    expect(buildKnowledgeDocumentQuery(state)).toEqual({
      pageNo: 4,
      pageSize: 20,
      keyword: '临边防护',
      indexStatus: 'SUCCESS'
    });
    expect(state.pageNo).toBe(4);
  });

  it('rejects an older response when a newer request targets the same base', () => {
    const guard = createKnowledgeDocumentRequestGuard();
    const first = guard.begin(10);
    const second = guard.begin(10);

    expect(guard.isCurrent(first, 10)).toBe(false);
    expect(guard.isCurrent(second, 10)).toBe(true);
  });

  it('rejects a response after the active base changes or requests are invalidated', () => {
    const guard = createKnowledgeDocumentRequestGuard();
    const request = guard.begin('base-a');

    expect(guard.isCurrent(request, 'base-b')).toBe(false);
    guard.invalidate();
    expect(guard.isCurrent(request, 'base-a')).toBe(false);
  });

  it('matches an operation only to the knowledge base where it started', () => {
    expect(isSameKnowledgeBase(10, '10')).toBe(true);
    expect(isSameKnowledgeBase(10, 11)).toBe(false);
  });
});
