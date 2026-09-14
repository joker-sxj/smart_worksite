import request from '../utils/request';
import { mockOcrRecord } from '../mocks/ocr';
import type { ID, OcrField, OcrRecord, OcrTypeTemplate, PageQuery, PageResult } from './types';
import { useModuleMock } from './mock';

const useMock = useModuleMock('VITE_USE_OCR_MOCK', false);
const mockRecords: OcrRecord[] = [mockOcrRecord];

function mockId() {
  return Number(`${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, '0')}`);
}

export async function fetchOcrRecords(params: PageQuery & { projectId: ID; ocrType?: string; status?: string; keyword?: string }) {
  if (useMock) {
    return {
      pageNo: params.pageNo || 1,
      pageSize: params.pageSize || 20,
      total: mockRecords.length,
      records: mockRecords
    } satisfies PageResult<OcrRecord>;
  }
  return request.get<PageResult<OcrRecord>>('/ocr/records', { params });
}

export async function fetchOcrTypes() {
  if (useMock) {
    return [
      { ocrType: 'ID_CARD', name: '身份证识别', requiredFields: ['姓名', '身份证号'] },
      { ocrType: 'LICENSE_PLATE', name: '车牌识别', requiredFields: ['车牌号'] },
      { ocrType: 'INVOICE', name: '发票识别', requiredFields: ['发票号码', '金额'] },
      { ocrType: 'CUSTOM', name: '自定义字段识别', requiredFields: [] }
    ] satisfies OcrTypeTemplate[];
  }
  return request.get<OcrTypeTemplate[]>('/ocr/types');
}

export async function submitOcrRecord(data: { projectId: ID; ocrType: string; file: File; invoiceType?: string; customFields?: string }) {
  if (useMock) {
    const now = new Date().toISOString();
    const id = mockId();
    const custom = data.ocrType === 'CUSTOM' && data.customFields
      ? JSON.parse(data.customFields).map((field: { fieldName?: string }, index: number) => ({
          fieldName: field.fieldName || `自定义字段${index + 1}`,
          fieldValue: '',
          confidence: 0,
          location: `自定义${index + 1}`
        }))
      : [];
    const record = {
      ...mockOcrRecord,
      id,
      recordId: id,
      taskId: id + 1,
      projectId: data.projectId,
      ocrType: data.ocrType as OcrRecord['ocrType'],
      fields: custom.length ? custom : mockOcrRecord.fields.map((item) => ({ ...item })),
      createdAt: now,
      updatedAt: now
    } satisfies OcrRecord;
    mockRecords.unshift(record);
    return { recordId: record.recordId, taskId: record.taskId, status: record.status };
  }
  const form = new FormData();
  form.append('projectId', String(data.projectId));
  form.append('ocrType', data.ocrType);
  form.append('file', data.file);
  if (data.invoiceType) form.append('invoiceType', data.invoiceType);
  if (data.customFields) form.append('customFields', data.customFields);
  return request.post<{ recordId: ID; taskId: ID; status: string }>('/ocr/records', form);
}

export async function fetchOcrRecord(recordId: ID) {
  if (useMock) {
    const item = mockRecords.find((record) => String(record.recordId) === String(recordId));
    if (!item) throw new Error(`OCR record not found: ${recordId}`);
    return item;
  }
  return request.get<OcrRecord>(`/ocr/records/${recordId}`);
}

export async function updateOcrFields(recordId: ID, fields: OcrField[]) {
  if (useMock) {
    const item = mockRecords.find((record) => String(record.recordId) === String(recordId));
    if (!item) throw new Error(`OCR record not found: ${recordId}`);
    item.fields = fields.map((field) => ({ ...field }));
    item.updatedAt = new Date().toISOString();
    return item;
  }
  return request.put<OcrRecord>(`/ocr/records/${recordId}/fields`, { fields });
}

export async function confirmOcrRecord(recordId: ID) {
  if (useMock) {
    const item = mockRecords.find((record) => String(record.recordId) === String(recordId));
    if (!item) throw new Error(`OCR record not found: ${recordId}`);
    item.manuallyConfirmed = true;
    item.confirmedAt = new Date().toISOString();
    return item;
  }
  return request.post<OcrRecord>(`/ocr/records/${recordId}/confirm`);
}

export async function retryOcrRecord(recordId: ID) {
  if (useMock) return { recordId, taskId: Number(recordId) + 1, status: 'PENDING' };
  return request.post<{ recordId: ID; taskId: ID; status: string }>(`/ocr/records/${recordId}/retry`);
}

export async function deleteOcrRecord(recordId: ID) {
  if (useMock) {
    const index = mockRecords.findIndex((record) => String(record.recordId) === String(recordId));
    if (index >= 0) mockRecords.splice(index, 1);
    return null;
  }
  return request.delete<null>(`/ocr/records/${recordId}`);
}

export async function fetchOcrDownloadResult(recordId: ID) {
  if (useMock) {
    const item = mockRecords.find((record) => String(record.recordId) === String(recordId));
    if (!item) throw new Error(`OCR record not found: ${recordId}`);
    return item;
  }
  return request.get<Record<string, unknown>>(`/ocr/records/${recordId}/download`);
}
