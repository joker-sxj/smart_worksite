
import request from '../utils/request';
import { mockReviewRecord, mockReviewTemplates } from '../mocks/review';
import type { ID, PageQuery, PageResult, ReviewRecord, ReviewTemplate } from './types';
import { useModuleMock } from './mock';

const useReviewRecordMock = useModuleMock('VITE_USE_REVIEW_RECORD_MOCK', false);
const useReviewTemplateMock = useModuleMock('VITE_USE_REVIEW_TEMPLATE_MOCK', false);
const mockRecords: ReviewRecord[] = [mockReviewRecord];

function mockId() {
  return Number(`${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, '0')}`);
}

function filterMockReviewTemplates(projectId?: ID) {
  return projectId ? mockReviewTemplates.filter((item) => String(item.projectId) === String(projectId)) : mockReviewTemplates;
}

function findMockReviewRecord(recordId: ID) {
  const item = mockRecords.find((record) => String(record.recordId) === String(recordId));
  if (!item) throw new Error(`审查记录不存在：${recordId}`);
  return item;
}

export async function fetchReviewTemplates(projectId?: ID) {
  if (useReviewTemplateMock) return filterMockReviewTemplates(projectId);
  return request.get<ReviewTemplate[]>('/review/templates', { params: { projectId, status: 'ENABLED' } });
}

export async function submitReviewRecord(data: { projectId: ID; templateId: ID; file: File; referenceFiles?: File[]; knowledgeBaseIds?: ID[] }) {
  if (useReviewRecordMock) {
    const now = new Date().toISOString();
    const id = mockId();
    const record = { ...mockReviewRecord, recordId: id, taskId: id + 1, projectId: data.projectId, templateId: data.templateId, createdAt: now, updatedAt: now } satisfies ReviewRecord;
    mockRecords.unshift(record);
    return { recordId: record.recordId, taskId: record.taskId, status: record.status };
  }
  const form = new FormData();
  form.append('projectId', String(data.projectId));
  form.append('templateId', String(data.templateId));
  form.append('file', data.file);
  for (const reference of data.referenceFiles || []) form.append('referenceFiles', reference);
  for (const knowledgeBaseId of data.knowledgeBaseIds || []) form.append('knowledgeBaseIds', String(knowledgeBaseId));
  return request.post<{ recordId: ID; taskId: ID; status: string }>('/review/records', form);
}

export async function fetchReviewRecord(recordId: ID) {
  if (useReviewRecordMock) return findMockReviewRecord(recordId);
  return request.get<ReviewRecord>(`/review/records/${recordId}`);
}


export async function fetchReviewRecords(params: PageQuery = {}) {
  if (useReviewRecordMock) return { pageNo: params.pageNo || 1, pageSize: params.pageSize || 20, total: mockRecords.length, records: mockRecords } satisfies PageResult<ReviewRecord>;
  return request.get<PageResult<ReviewRecord>>('/review/records', { params });
}

export async function retryReviewRecord(recordId: ID) {
  if (useReviewRecordMock) return { ...findMockReviewRecord(recordId), status: 'QUEUED' } satisfies ReviewRecord;
  return request.post<ReviewRecord>(`/review/records/${recordId}/retry`);
}

export async function deleteReviewRecord(recordId: ID) {
  if (useReviewRecordMock) return null;
  return request.delete<null>(`/review/records/${recordId}`);
}

export async function archiveReviewRecord(recordId: ID) {
  if (useReviewRecordMock) return { ...findMockReviewRecord(recordId), status: 'ARCHIVED' } satisfies ReviewRecord;
  return request.post<ReviewRecord>(`/review/records/${recordId}/archive`);
}

export async function updateReviewIssue(recordId: ID, issueId: string, data: { status: string; comment?: string }) {
  if (useReviewRecordMock) return findMockReviewRecord(recordId);
  return request.put<ReviewRecord>(`/review/records/${recordId}/issues/${issueId}`, data);
}
