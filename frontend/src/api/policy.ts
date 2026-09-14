import { request } from '../utils/request';
import type { ID, PageQuery, PageResult, PolicyArticle, PolicyCrawlTask, PolicySource, PolicySourceForm } from './types';

export function fetchPolicySources(params: PageQuery = {}) { return request.get<PageResult<PolicySource>>('/policy/sources', { params }); }
export function createPolicySource(data: PolicySourceForm & { projectId: ID }) { return request.post<PolicySource>('/policy/sources', data); }
export function updatePolicySource(sourceId: ID, data: PolicySourceForm) { return request.put<PolicySource>(`/policy/sources/${sourceId}`, data); }
export function deletePolicySource(sourceId: ID) { return request.delete<null>(`/policy/sources/${sourceId}`); }
export function createPolicyCrawlTask(data: { projectId: ID; sourceId?: ID }) { return request.post<PolicyCrawlTask>('/policy/crawl-tasks', data); }
export function fetchPolicyCrawlTasks(params: PageQuery & { sourceId?: ID } = {}) { return request.get<PageResult<PolicyCrawlTask>>('/policy/crawl-tasks', { params }); }
export function fetchPolicyArticles(params: PageQuery & { sourceId?: ID; indexStatus?: string } = {}) { return request.get<PageResult<PolicyArticle>>('/policy/articles', { params }); }
