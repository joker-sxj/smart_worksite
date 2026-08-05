import request from '../utils/request';
import type { AxiosResponse } from 'axios';
import type { ID, PageQuery, PageResult, ReviewTemplate } from './types';

export type TemplateItem = ReviewTemplate & {
  templateCategory?: string;
  scenario?: string;
  versionNo?: string;
  description?: string;
};

export interface TemplateQuery extends PageQuery {
  templateCategory?: 'REPORT' | 'REVIEW' | string;
  templateType?: string;
}

export interface TemplateUpdateRequest {
  templateName: string;
  templateType: string;
  scenario?: string;
  versionNo: string;
  description?: string;
}

export interface TemplateUploadRequest {
  projectId: ID;
  templateCategory: 'REPORT' | 'REVIEW' | string;
  templateName: string;
  templateType: string;
  versionNo: string;
  file: File;
  scenario?: string;
  description?: string;
}

export interface TemplatePreviewFile {
  blob: Blob;
  fileName: string;
  contentType: string;
}

export interface TemplateVariableDescription {
  variableName: string;
  description: string;
  dataSourceIds: ID[];
}

function cleanParams(params: TemplateQuery) {
  return Object.fromEntries(Object.entries(params).filter(([, value]) => value !== '' && value !== undefined && value !== null));
}

export async function uploadTemplate(data: TemplateUploadRequest) {
  const form = new FormData();
  form.append('projectId', String(data.projectId));
  form.append('templateName', data.templateName);
  form.append('templateType', data.templateType);
  form.append('versionNo', data.versionNo);
  if (data.scenario) form.append('scenario', data.scenario);
  if (data.description) form.append('description', data.description);
  form.append('file', data.file);
  if (data.templateCategory === 'REPORT') return request.post<TemplateItem>('/templates/report', form);
  if (data.templateCategory === 'REVIEW') return request.post<TemplateItem>('/templates/review', form);
  form.append('templateCategory', data.templateCategory);
  return request.post<TemplateItem>('/templates', form);
}

export async function fetchTemplates(params: TemplateQuery = {}) {
  return request.get<PageResult<TemplateItem>>('/templates', { params: cleanParams(params) });
}

export function fetchTemplateDetail(templateId: ID) {
  return request.get<TemplateItem>(`/templates/${templateId}`);
}

export async function fetchTemplatePreview(templateId: ID): Promise<TemplatePreviewFile> {
  const response = await request.request<Blob, AxiosResponse<Blob>>({
    url: `/templates/${templateId}/preview`,
    method: 'GET',
    responseType: 'blob',
    transformResponse: [(data) => data]
  });
  const contentDisposition = String(response.headers['content-disposition'] || '');
  const utf8Name = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const normalName = contentDisposition.match(/filename="?([^";]+)"?/i)?.[1];
  let fileName = `template-${templateId}`;
  try {
    fileName = decodeURIComponent(utf8Name || normalName || fileName);
  } catch {
    fileName = utf8Name || normalName || fileName;
  }
  return {
    blob: response.data,
    fileName,
    contentType: String(response.headers['content-type'] || response.data.type || 'application/octet-stream')
  };
}

export function fetchTemplateVariableDescriptions(templateId: ID) {
  return request.get<TemplateVariableDescription[]>(`/templates/${templateId}/variables/descriptions`);
}

export function updateTemplateVariableDescriptions(templateId: ID, variables: TemplateVariableDescription[]) {
  return request.put<TemplateVariableDescription[]>(`/templates/${templateId}/variables/descriptions`, { variables });
}

export function fetchReviewTemplateDetail(templateId: ID) {
  return request.get<TemplateItem>(`/templates/${templateId}`);
}

export function updateTemplate(templateId: ID, data: TemplateUpdateRequest) {
  return request.put<TemplateItem>(`/templates/${templateId}`, data);
}

export function enableTemplate(templateId: ID) {
  return request.post<{ templateId: ID; status: string }>(`/templates/${templateId}/enable`);
}

export function disableTemplate(templateId: ID) {
  return request.post<{ templateId: ID; status: string }>(`/templates/${templateId}/disable`);
}

export function deleteTemplate(templateId: ID) {
  return request.delete<null>(`/templates/${templateId}`);
}
