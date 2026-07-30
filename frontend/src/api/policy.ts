import { mockPolicyArticles, mockPolicySources, mockPolicyTasks } from '../mocks/policy';
import { request } from '../utils/request';
import type { ID, PageQuery, PageResult, PolicyArticle, PolicyCrawlTask, PolicySource, PolicySourceForm } from './types';
import { useModuleMock } from './mock';

const useMock = useModuleMock('VITE_USE_POLICY_MOCK', false);
const sourceState = useMock ? [...mockPolicySources] : [];
const taskState = useMock ? [...mockPolicyTasks] : [];
const articleState = useMock ? [...mockPolicyArticles] : [];

function mockId() {
  return Number(`${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, '0')}`);
}

function paginate<T>(records: T[], params: PageQuery = {}) {
  const pageNo = params.pageNo || 1;
  const pageSize = params.pageSize || 20;
  const start = (pageNo - 1) * pageSize;
  return { pageNo, pageSize, total: records.length, records: records.slice(start, start + pageSize) } satisfies PageResult<T>;
}

function filterProject<T extends { projectId?: ID }>(records: T[], projectId?: ID) {
  return projectId ? records.filter((item) => String(item.projectId) === String(projectId)) : records;
}

export async function fetchPolicySources(params: PageQuery = {}) {
  if (useMock) return paginate(filterProject(sourceState, params.projectId), params);
  return request.get<PageResult<PolicySource>>('/policy/sources', { params });
}

export async function createPolicySource(data: PolicySourceForm & { projectId: ID }) {
  if (useMock) {
    const now = new Date().toISOString();
    const created = { ...data, sourceId: mockId(), status: 'ENABLED', createdAt: now, updatedAt: now } satisfies PolicySource;
    sourceState.unshift(created);
    return created;
  }
  return request.post<PolicySource>('/policy/sources', data);
}

export async function updatePolicySource(sourceId: ID, data: PolicySourceForm) {
  if (useMock) {
    const index = sourceState.findIndex((item) => String(item.sourceId) === String(sourceId));
    if (index < 0) throw new Error(`政策源不存在：${sourceId}`);
    sourceState[index] = { ...sourceState[index], ...data, updatedAt: new Date().toISOString() };
    return sourceState[index];
  }
  return request.put<PolicySource>(`/policy/sources/${sourceId}`, data);
}

export async function deletePolicySource(sourceId: ID) {
  if (useMock) {
    const index = sourceState.findIndex((item) => String(item.sourceId) === String(sourceId));
    if (index >= 0) sourceState.splice(index, 1);
    return null;
  }
  return request.delete<null>(`/policy/sources/${sourceId}`);
}

export async function createPolicyCrawlTask(data: { projectId: ID; sourceId?: ID }) {
  if (useMock) {
    const now = new Date().toISOString();
    const source = sourceState.find((item) => String(item.sourceId) === String(data.sourceId));
    const task = {
      taskId: mockId(),
      projectId: data.projectId,
      sourceId: data.sourceId,
      sourceName: source?.name || '全部政策源',
      status: 'SUCCESS',
      progress: 100,
      fetchedCount: 3,
      indexedCount: 3,
      message: 'Mock 演示：已模拟完成政策资讯采集，真实爬取需后端政策模块实现。',
      startedAt: now,
      finishedAt: now,
      createdAt: now
    } satisfies PolicyCrawlTask;
    taskState.unshift(task);
    if (source) {
      source.lastCrawledAt = now;
      source.updatedAt = now;
    }
    return task;
  }
  return request.post<PolicyCrawlTask>('/policy/crawl-tasks', data);
}

export async function fetchPolicyCrawlTasks(params: PageQuery & { sourceId?: ID } = {}) {
  if (useMock) {
    const records = filterProject(taskState, params.projectId).filter((item) => !params.sourceId || String(item.sourceId) === String(params.sourceId));
    return paginate(records, params);
  }
  return request.get<PageResult<PolicyCrawlTask>>('/policy/crawl-tasks', { params });
}

export async function fetchPolicyArticles(params: PageQuery & { sourceId?: ID; indexStatus?: string } = {}) {
  if (useMock) {
    const records = filterProject(articleState, params.projectId).filter((item) => {
      const keywordMatched = !params.keyword || item.title.includes(params.keyword) || item.summary.includes(params.keyword);
      const sourceMatched = !params.sourceId || String(item.sourceId) === String(params.sourceId);
      const statusMatched = !params.indexStatus || item.indexStatus === params.indexStatus;
      return keywordMatched && sourceMatched && statusMatched;
    });
    return paginate(records, params);
  }
  return request.get<PageResult<PolicyArticle>>('/policy/articles', { params });
}
