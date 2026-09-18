export interface KnowledgeDocumentPaging {
  pageNo: number;
  pageSize: number;
  total: number;
  keyword: string;
  indexStatus: string;
}

export interface KnowledgeDocumentQuery {
  pageNo: number;
  pageSize: number;
  keyword?: string;
  indexStatus?: string;
}

export function isSameKnowledgeBase(first: string | number, second: string | number) {
  return String(first) === String(second);
}

export function createKnowledgeDocumentRequestGuard() {
  let version = 0;

  return {
    begin(baseId: string | number) {
      return { version: ++version, baseId: String(baseId) };
    },
    isCurrent(request: { version: number; baseId: string }, activeBaseId: string | number) {
      return request.version === version && request.baseId === String(activeBaseId);
    },
    invalidate() {
      version += 1;
    }
  };
}

export function buildKnowledgeDocumentQuery(state: KnowledgeDocumentPaging): KnowledgeDocumentQuery {
  const keyword = state.keyword.trim();
  return {
    pageNo: state.pageNo,
    pageSize: state.pageSize,
    ...(keyword ? { keyword } : {}),
    ...(state.indexStatus ? { indexStatus: state.indexStatus } : {})
  };
}

export function resetKnowledgeDocumentPage(state: KnowledgeDocumentPaging): KnowledgeDocumentPaging {
  return { ...state, pageNo: 1 };
}

export function updateKnowledgeDocumentFilters(
  state: KnowledgeDocumentPaging,
  filters: Pick<KnowledgeDocumentPaging, 'keyword' | 'indexStatus'>
): KnowledgeDocumentPaging {
  return { ...state, ...filters, pageNo: 1 };
}

export function knowledgeDocumentPageAfterDelete(state: KnowledgeDocumentPaging, pageRecordCount: number) {
  return pageRecordCount === 1 && state.pageNo > 1 ? state.pageNo - 1 : state.pageNo;
}
