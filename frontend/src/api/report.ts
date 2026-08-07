import request, { downloadApiFile, downloadTextFile } from '../utils/request';
import { mockReports } from '../mocks/report';
import type { ID, PageQuery, PageResult, ReportItem, ReportVariableItem } from './types';
import type { TemplateItem } from './template';
import { useModuleMock } from './mock';

const useMock = useModuleMock('VITE_USE_REPORT_MOCK', false);

interface ReportCreateRequest {
  projectId: ID;
  reportName: string;
  reportType: string;
  templateId: ID;
  knowledgeBaseIds: ID[];
  dataSourceIds: ID[];
}

export type ReportTemplate = TemplateItem;
type DownloadUrlResponse = string | { url: string; [key: string]: unknown };

export async function fetchReportTemplates(projectId?: ID) {
  return request.get<ReportTemplate[]>('/report/templates', { params: { projectId, status: 'ENABLED' } });
}

export async function fetchReportTemplateVariables(templateId: ID) {
  if (useMock) return ['projectName', 'month'];
  return request.get<string[]>(`/report/templates/${templateId}/variables`);
}

export async function createReport(data: ReportCreateRequest) {
  if (useMock) return { reportId: 10003, taskId: 9503, status: 'PROCESSING' };
  return request.post<{ reportId: ID; taskId: ID; status: string }>('/reports', data);
}

export async function fetchReports(params: PageQuery = {}) {
  const records = mockReports.filter((item) => !params.projectId || String(item.projectId) === String(params.projectId));
  if (useMock) {
    return { pageNo: params.pageNo || 1, pageSize: params.pageSize || 20, total: records.length, records } satisfies PageResult<ReportItem>;
  }
  return request.get<PageResult<ReportItem>>('/reports', { params });
}

export async function fetchReportDetail(reportId: ID) {
  if (useMock) {
    const item = mockReports.find((report) => String(report.reportId) === String(reportId));
    if (!item) throw new Error(`报告不存在：${reportId}`);
    return item;
  }
  return request.get<ReportItem>(`/reports/${reportId}`);
}

export async function fetchReportVariables(reportId: ID) {
  if (useMock) return [];
  return request.get<ReportVariableItem[]>(`/reports/${reportId}/variables`);
}

export async function regenerateReport(reportId: ID) {
  if (useMock) return { reportId, taskId: 9504, status: 'PROCESSING' };
  return request.post<{ reportId: ID; taskId: ID; status: string }>(`/reports/${reportId}/regenerate`);
}

export async function fetchReportDownloadUrl(reportId: ID, format: 'WORD' | 'PDF' = 'WORD') {
  const result = await request.get<DownloadUrlResponse>(`/reports/${reportId}/download`, { params: { format } });
  return typeof result === 'string' ? result : result.url;
}

export async function downloadReport(reportId: ID, format: 'WORD' | 'PDF' = 'WORD', filename?: string) {
  if (useMock) return downloadTextFile(filename || `report-${reportId}.${format === 'PDF' ? 'pdf' : 'docx'}`, 'mock report content');
  return downloadApiFile(`/reports/${reportId}/download-file`, { params: { format }, filename });
}
