import request from '../utils/request';
import { mockSources } from '../mocks/datasource';
import type { DataSourceConnectionTestResult, DataSourceForm, DataSourceItem, DataSourceQueryResult, DataSourceSchema, ID, PageQuery, PageResult } from './types';
import { useModuleMock } from './mock';

const useMock = useModuleMock('VITE_USE_DATASOURCE_MOCK', false);
const DATABASE_QUERY_TIMEOUT_MS = 120_000;

export async function fetchDataSources(params: PageQuery = {}) {
  if (useMock) {
    const records = mockSources.filter((item) => !params.projectId || String(item.projectId) === String(params.projectId));
    return { pageNo: params.pageNo || 1, pageSize: params.pageSize || 20, total: records.length, records } satisfies PageResult<DataSourceItem>;
  }
  return request.get<PageResult<DataSourceItem>>('/data-sources', { params });
}

export async function fetchDataSourceDetail(dataSourceId: ID) {
  if (useMock) {
    const item = mockSources.find((source) => String(source.dataSourceId) === String(dataSourceId));
    if (!item) throw new Error(`数据源不存在：${dataSourceId}`);
    return item;
  }
  return request.get<DataSourceItem>(`/data-sources/${dataSourceId}`);
}

export async function createDataSource(data: DataSourceForm & { projectId: ID }) {
  if (useMock) return { ...data, dataSourceId: Date.now(), status: 'ENABLED', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() } as DataSourceItem;
  return request.post<DataSourceItem>('/data-sources', data);
}

export async function updateDataSource(dataSourceId: ID, data: DataSourceForm) {
  if (useMock) {
    const item = mockSources.find((source) => String(source.dataSourceId) === String(dataSourceId));
    if (!item) throw new Error(`数据源不存在：${dataSourceId}`);
    return { ...item, ...data, dataSourceId, updatedAt: new Date().toISOString() } as DataSourceItem;
  }
  return request.put<DataSourceItem>(`/data-sources/${dataSourceId}`, data);
}

export async function testDataSource(dataSourceId: ID) {
  if (useMock) return { dataSourceId, success: true, message: '连接成功' } satisfies DataSourceConnectionTestResult;
  return request.post<DataSourceConnectionTestResult>(`/data-sources/${dataSourceId}/test`);
}

export async function inspectDataSourceSchema(dataSourceId: ID) {
  if (useMock) return { dataSourceId, dbType: 'MYSQL', tables: [] } satisfies DataSourceSchema;
  return request.get<DataSourceSchema>(`/data-sources/${dataSourceId}/schema`);
}

export async function enableDataSource(dataSourceId: ID) {
  if (useMock) {
    const item = mockSources.find((source) => String(source.dataSourceId) === String(dataSourceId));
    if (!item) throw new Error(`数据源不存在：${dataSourceId}`);
    return { ...item, dataSourceId, status: 'ENABLED' } as DataSourceItem;
  }
  return request.post<DataSourceItem>(`/data-sources/${dataSourceId}/enable`);
}

export async function disableDataSource(dataSourceId: ID) {
  if (useMock) {
    const item = mockSources.find((source) => String(source.dataSourceId) === String(dataSourceId));
    if (!item) throw new Error(`数据源不存在：${dataSourceId}`);
    return { ...item, dataSourceId, status: 'DISABLED' } as DataSourceItem;
  }
  return request.post<DataSourceItem>(`/data-sources/${dataSourceId}/disable`);
}

export async function deleteDataSource(dataSourceId: ID) {
  if (useMock) return null;
  return request.delete<null>(`/data-sources/${dataSourceId}`);
}

export async function queryDataSource(data: { projectId: ID; question: string; dataSourceId: ID; context?: string }) {
  if (useMock) return { sql: "select count(*) as issue_count from safety_issue where status <> 'CLOSED';", columns: ['issue_count'], rows: [{ issue_count: 12 }], summary: '当前未闭环安全问题 12 项。' } satisfies DataSourceQueryResult;
  return request.post<DataSourceQueryResult>('/ai/database/query', data, {
    timeout: DATABASE_QUERY_TIMEOUT_MS
  });
}
