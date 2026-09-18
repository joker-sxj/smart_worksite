import request from '../utils/request';
import { mockKnowledgeBases, mockKnowledgeDocuments } from '../mocks/knowledge';
import type { ID, KnowledgeBase, KnowledgeDocument, PageQuery, PageResult } from './types';
import { useModuleMock } from './mock';

const useMock = useModuleMock('VITE_USE_KNOWLEDGE_MOCK', false);
const mockBaseStorageKey = 'smart_worksite_mock_knowledge_bases';
const mockDocumentStorageKey = 'smart_worksite_mock_knowledge_documents';

function readMockState<T>(key: string, fallback: T[]) {
  const raw = localStorage.getItem(key);
  if (!raw) return [...fallback];
  try {
    return JSON.parse(raw) as T[];
  } catch (error) {
    console.error(`Mock knowledge state is corrupted: ${key}`, error);
    throw new Error(`本地知识库 mock 缓存解析失败：${key}`);
  }
}

const mockBaseState = useMock ? readMockState<KnowledgeBase>(mockBaseStorageKey, mockKnowledgeBases) : [];
const mockDocumentState = useMock ? readMockState<KnowledgeDocument>(mockDocumentStorageKey, mockKnowledgeDocuments) : [];

function saveMockState() {
  localStorage.setItem(mockBaseStorageKey, JSON.stringify(mockBaseState));
  localStorage.setItem(mockDocumentStorageKey, JSON.stringify(mockDocumentState));
}

function createMockId() {
  return Number(`${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, '0')}`);
}

export async function fetchKnowledgeBases(projectId: ID, params: PageQuery = {}) {
  if (useMock) return mockBaseState.filter((item) => String(item.projectId) === String(projectId));
  const page = await request.get<PageResult<KnowledgeBase>>(`/projects/${projectId}/knowledge-bases`, { params });
  return page.records;
}

export async function createKnowledgeBase(projectId: ID, data: Pick<KnowledgeBase, 'name' | 'description' | 'domain'>) {
  if (useMock) {
    const now = new Date().toISOString();
    const id = createMockId();
    const created = { knowledgeBaseId: id, projectId, domain: data.domain, name: data.name, description: data.description || '', status: 'ACTIVE', createdAt: now, updatedAt: now } satisfies KnowledgeBase;
    mockBaseState.unshift(created);
    saveMockState();
    return created;
  }
  return request.post<KnowledgeBase>(`/projects/${projectId}/knowledge-bases`, data);
}

export async function fetchKnowledgeBaseDetail(knowledgeBaseId: ID) {
  if (useMock) {
    const item = mockBaseState.find((base) => String(base.knowledgeBaseId) === String(knowledgeBaseId));
    if (!item) throw new Error(`知识库不存在：${knowledgeBaseId}`);
    return item;
  }
  return request.get<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}`);
}

export async function updateKnowledgeBase(knowledgeBaseId: ID, data: Pick<KnowledgeBase, 'name' | 'description' | 'domain'>) {
  if (useMock) {
    const item = mockBaseState.find((base) => String(base.knowledgeBaseId) === String(knowledgeBaseId));
    if (!item) throw new Error(`知识库不存在：${knowledgeBaseId}`);
    Object.assign(item, data, { updatedAt: new Date().toISOString() });
    saveMockState();
    return item;
  }
  return request.put<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}`, data);
}

export async function enableKnowledgeBase(knowledgeBaseId: ID) {
  if (useMock) {
    const item = mockBaseState.find((base) => String(base.knowledgeBaseId) === String(knowledgeBaseId));
    if (item) { item.status = 'ENABLED'; item.updatedAt = new Date().toISOString(); saveMockState(); return item; }
    throw new Error(`知识库不存在：${knowledgeBaseId}`);
  }
  return request.post<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}/enable`);
}

export async function disableKnowledgeBase(knowledgeBaseId: ID) {
  if (useMock) {
    const item = mockBaseState.find((base) => String(base.knowledgeBaseId) === String(knowledgeBaseId));
    if (item) { item.status = 'DISABLED'; item.updatedAt = new Date().toISOString(); saveMockState(); return item; }
    throw new Error(`知识库不存在：${knowledgeBaseId}`);
  }
  return request.post<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}/disable`);
}

export async function deleteKnowledgeBase(knowledgeBaseId: ID) {
  if (useMock) {
    const index = mockBaseState.findIndex((base) => String(base.knowledgeBaseId) === String(knowledgeBaseId));
    if (index < 0) throw new Error(`知识库不存在：${knowledgeBaseId}`);
    mockBaseState.splice(index, 1);
    saveMockState();
    return null;
  }
  return request.delete<null>(`/knowledge-bases/${knowledgeBaseId}`);
}

export async function uploadKnowledgeDocument(knowledgeBaseId: ID, file: File) {
  if (useMock) {
    const now = new Date().toISOString();
    const base = mockBaseState.find((item) => String(item.knowledgeBaseId) === String(knowledgeBaseId));
    if (!base) throw new Error(`知识库不存在，无法上传 mock 文档：${knowledgeBaseId}`);
    const id = createMockId();
    const created = { documentId: id, projectId: base.projectId, knowledgeBaseId, fileId: id + 1, title: file.name, sourceType: 'UPLOAD', indexStatus: 'PENDING', taskId: id + 2, createdAt: now, updatedAt: now } satisfies KnowledgeDocument;
    mockDocumentState.unshift(created);
    saveMockState();
    return created;
  }
  const form = new FormData();
  form.append('file', file);
  return request.post<KnowledgeDocument>(`/knowledge-bases/${knowledgeBaseId}/documents`, form);
}

export async function triggerDocumentIndex(documentId: ID) {
  if (useMock) {
    const item = mockDocumentState.find((doc) => String(doc.documentId) === String(documentId));
    if (item) {
      item.indexStatus = 'INDEXING';
      item.updatedAt = new Date().toISOString();
      saveMockState();
      return item;
    }
    throw new Error(`知识文档不存在，无法提交 mock 入库任务：${documentId}`);
  }
  return request.post<KnowledgeDocument>(`/knowledge-documents/${documentId}/index`);
}

export function buildMockKnowledgeDocumentPage(
  state: KnowledgeDocument[],
  knowledgeBaseId: ID,
  params: PageQuery & { indexStatus?: string } = {}
): PageResult<KnowledgeDocument> {
  const pageNo = params.pageNo || 1;
  const pageSize = params.pageSize || 20;
  const keyword = params.keyword?.trim() || '';
  const records = state.filter((item) => (
    String(item.knowledgeBaseId) === String(knowledgeBaseId)
    && (!keyword || item.title.includes(keyword))
    && (!params.indexStatus || item.indexStatus === params.indexStatus)
  ));
  const offset = (pageNo - 1) * pageSize;
  return { pageNo, pageSize, total: records.length, records: records.slice(offset, offset + pageSize) };
}

export async function fetchKnowledgeDocuments(knowledgeBaseId: ID, params: PageQuery & { indexStatus?: string } = {}) {
  if (useMock) return buildMockKnowledgeDocumentPage(mockDocumentState, knowledgeBaseId, params);
  return request.get<PageResult<KnowledgeDocument>>(`/knowledge-bases/${knowledgeBaseId}/documents`, { params });
}

export async function fetchKnowledgeDocumentDetail(documentId: ID) {
  if (useMock) {
    const item = mockDocumentState.find((doc) => String(doc.documentId) === String(documentId));
    if (!item) throw new Error(`知识文档不存在：${documentId}`);
    return item;
  }
  return request.get<KnowledgeDocument>(`/knowledge-documents/${documentId}`);
}

export async function deleteKnowledgeDocument(documentId: ID) {
  if (useMock) {
    const index = mockDocumentState.findIndex((doc) => String(doc.documentId) === String(documentId));
    if (index < 0) throw new Error(`知识文档不存在：${documentId}`);
    mockDocumentState.splice(index, 1);
    saveMockState();
    return null;
  }
  return request.delete<null>(`/knowledge-documents/${documentId}`);
}
