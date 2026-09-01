export type ID = string | number;
export type Status = 'PENDING' | 'QUEUED' | 'RUNNING' | 'PROCESSING' | 'INDEXING' | 'COMPLETED' | 'PARTIAL_SUCCESS' | 'SUCCESS' | 'FAILED' | 'RETRYING' | 'CANCELED' | 'ACTIVE' | 'ENABLED' | 'DISABLED' | 'ARCHIVED';

export interface PageQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  status?: string;
  projectId?: ID;
}

export interface PageResult<T> {
  pageNo: number;
  pageSize: number;
  total: number;
  records: T[];
}

export interface UserInfo {
  id: ID;
  username: string;
  realName: string;
  roles: string[];
  permissions: string[];
  buttonPermissions?: string[];
  projects?: UserProject[];
  defaultProjectId?: ID;
  createdAt: string;
  updatedAt: string;
}

export interface UserProject {
  projectId: ID;
  projectName: string;
  projectCode: string;
  status: string;
  projectRole: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: UserInfo;
}

export interface ProjectItem {
  id?: ID;
  projectId: ID;
  name?: string;
  code?: string;
  address?: string;
  projectName: string;
  projectCode: string;
  location?: string;
  status: Status | string;
  description?: string;
  taskId?: ID;
  fileId?: ID;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectCreateForm {
  projectName: string;
  projectCode: string;
  location?: string;
  description?: string;
}

export interface ProjectUpdateForm {
  projectName: string;
  projectCode: string;
  location?: string;
  description?: string;
}

export interface ProjectStatistics {
  projectId: ID;
  memberCount: number;
  knowledgeBaseCount: number;
  reportCount: number;
  dataSourceCount: number;
  qaCount: number;
  reviewCount: number;
  ocrCount: number;
  fileStorageBytes: number;
}

export interface ProjectSettings {
  projectId?: ID;
  defaultKnowledgeBaseId?: ID | null;
  policyKnowledgeBaseId?: ID | null;
  defaultReportTemplateId?: ID | null;
  dataRetentionDays?: number;
  uploadMaxSizeMb?: number;
  allowedFileTypes?: string[];
  internetPolicyCrawlerEnabled?: boolean;
  defaultQaRouteMode?: 'AUTO' | 'MODEL' | 'KNOWLEDGE' | 'DATABASE' | 'MIXED' | string;
  defaultOcrLanguage?: string;
  defaultReportExportFormat?: 'WORD' | 'PDF' | string;
}
export interface FileObject {
  fileId: ID;
  objectName?: string;
  projectId: ID;
  bizType?: string;
  bizId?: ID;
  fileName: string;
  fileExt?: string;
  contentType?: string;
  fileSize: number;
  fileHash?: string;
  status: Status;
  metadata?: string;
  previewSupported?: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface FileAccessUrl {
  fileId: ID;
  url: string;
  expiresAt: string;
  previewSupported?: boolean;
}

export interface KnowledgeBase {
  knowledgeBaseId: ID;
  projectId: ID;
  domain?: string;
  knowledgeBaseType?: 'PROJECT' | 'POLICY' | string;
  name: string;
  description: string;
  status: Status;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocument {
  documentId: ID;
  projectId: ID;
  knowledgeBaseId: ID;
  fileId?: ID;
  title: string;
  fileExt?: string;
  contentType?: string;
  sourceType?: string;
  indexStatus: Status;
  taskId?: ID;
  errorMessage?: string;
  versionNo?: number;
  createdAt: string;
  updatedAt: string;
}

export interface QaSession {
  sessionId: ID;
  projectId: ID;
  title: string;
  status: Status;
  createdAt: string;
  updatedAt: string;
}

export interface QaReference {
  title: string;
  sourceType: 'KNOWLEDGE' | 'DATABASE' | 'POLICY';
  page?: string;
  score: number;
  documentId?: ID;
}

export type QaEvidenceStatus = 'SUFFICIENT' | 'PARTIAL' | 'INSUFFICIENT' | 'CONFLICT' | 'VALIDITY_UNKNOWN' | 'RETRIEVAL_DEGRADED' | 'TIMEOUT';

export interface QaRetrievalDiagnostics extends Record<string, unknown> {
  evidenceStatus?: QaEvidenceStatus | string;
}

export interface QaMessage {
  messageId: ID;
  sessionId: ID;
  projectId: ID;
  question?: string;
  answer?: string;
  routeMode?: 'AUTO' | 'MODEL' | 'KNOWLEDGE' | 'DATABASE' | 'MIXED';
  references?: QaReference[];
  retrievalDiagnostics?: QaRetrievalDiagnostics;
  evidenceStatus?: QaEvidenceStatus | string;
  feedback?: Record<string, unknown>;
  status: Status;
  taskId?: ID;
  errorMessage?: string;
  needClarification?: boolean;
  clarificationQuestions?: string[];
  providerTraceId?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewTemplate {
  id: ID;
  templateId: ID;
  projectId: ID;
  taskId?: ID;
  fileId: ID;
  templateName: string;
  templateType: string;
  status: Status;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewIssue {
  issueId: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  location: string;
  ruleName: string;
  description: string;
  suggestion: string;
  status?: 'OPEN' | 'PROCESSING' | 'RESOLVED' | 'IGNORED' | string;
  comment?: string;
}

export interface ReviewRecord {
  recordId: ID;
  projectId: ID;
  taskId?: ID;
  fileId?: ID;
  templateId: ID;
  status: Status;
  issues: ReviewIssue[];
  result?: Record<string, unknown>;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ReportItem {
  id: ID;
  reportId: ID;
  projectId: ID;
  taskId: ID;
  fileId?: ID;
  reportName: string;
  reportType: string;
  templateId: ID;
  engineType?: string;
  version: string;
  status: Status;
  progress: number;
  previewUrl?: string;
  errorMessage?: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ReportVariableItem {
  variableId: ID;
  reportId: ID;
  knowledgeBaseId: ID;
  variableName: string;
  variableDescription: string;
  variableValue?: string;
  sortNo: number;
  status: Status;
  providerTraceId?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  updatedAt?: string;
}

export interface UserItem {
  id: ID;
  username: string;
  displayName: string;
  phone?: string;
  email?: string;
  status: string;
  roles: string[];
  lastLoginAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface UserCreateForm {
  username: string;
  password: string;
  displayName: string;
  phone?: string;
  email?: string;
  roleCodes?: string[];
}

export interface UserUpdateForm {
  displayName: string;
  phone?: string;
  email?: string;
  roleCodes?: string[];
}

export interface RoleItem {
  id: ID;
  roleCode: string;
  roleName: string;
  description?: string;
  status: string;
  permissionIds: ID[];
  createdAt: string;
  updatedAt: string;
}

export interface RoleCreateForm {
  roleCode: string;
  roleName: string;
  description?: string;
  permissionIds?: ID[];
}

export interface RoleUpdateForm {
  roleName: string;
  description?: string;
  permissionIds?: ID[];
}

export interface PermissionItem {
  id: ID;
  permissionCode: string;
  permissionName: string;
  permissionType: string;
  parentId?: ID;
}

export interface ProjectMemberItem {
  id: ID;
  projectId: ID;
  userId: ID;
  username: string;
  displayName: string;
  projectRole: string;
  status: string;
  createdAt: string;
}

export interface OcrField {
  fieldKey?: string;
  fieldName: string;
  fieldValue: string;
  confidence: number;
  location: string;
  pageNo?: number;
  evidence?: string;
  revised?: boolean;
}

export interface OcrRecord {
  id: ID;
  recordId: ID;
  projectId: ID;
  taskId: ID;
  fileId: ID;
  ocrType: 'ID_CARD' | 'LICENSE_PLATE' | 'INVOICE' | 'CUSTOM';
  status: Status;
  progress: number;
  fields: OcrField[];
  createdAt: string;
  updatedAt: string;
}

export interface TaskStageLog {
  id: ID;
  taskId: ID;
  attemptNo?: number;
  stageCode: string;
  stageName?: string;
  status: Status;
  message?: string;
  inputSummary?: string;
  outputSummary?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  costMs?: number;
  createdAt: string;
}

export interface TaskDetail {
  taskId: ID;
  projectId: ID;
  taskType: string;
  bizType?: string;
  bizId?: ID;
  status: Status;
  progress?: number;
  currentStage?: string;
  retryCount?: number;
  maxRetryCount?: number;
  cancelRequested?: boolean;
  errorMessage?: string;
  stageLogs?: TaskStageLog[];
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectMember {
  id: ID;
  projectId: ID;
  userId: ID;
  realName: string;
  roleName: string;
  permissions: string[];
  status: Status;
  createdAt: string;
}

export interface DataSourceItem {
  id?: ID;
  dataSourceId: ID;
  projectId: ID;
  name: string;
  dbType: 'MYSQL' | 'POSTGRESQL' | 'KINGBASE' | string;
  jdbcUrl: string;
  username?: string;
  status: Status | string;
  createdAt: string;
  updatedAt: string;
}

export interface DataSourceForm {
  projectId?: ID;
  name: string;
  dbType: string;
  jdbcUrl: string;
  username: string;
  password?: string;
}

export interface DataSourceConnectionTestResult {
  dataSourceId: ID;
  success: boolean;
  message: string;
}

export interface DataSourceSchema {
  dataSourceId: ID;
  dbType: string;
  catalog?: string;
  schema?: string;
  tables: Array<{
    tableName: string;
    tableType?: string;
    remarks?: string;
    columns: Array<{ columnName: string; typeName: string; columnSize?: number; decimalDigits?: number; nullable?: boolean; remarks?: string }>;
  }>;
}

export interface TaskStatistics {
  projectId?: ID;
  statusCounts: Record<string, number>;
  queuedCount: number;
  runningCount: number;
  failedCount: number;
}

export interface OcrTypeTemplate {
  ocrType: string;
  name: string;
  requiredFields: string[];
}

export interface DataSourceQueryResult {
  sql?: string;
  columns: string[];
  rows: Record<string, unknown>[];
  summary?: string;
}

export interface PolicySource {
  sourceId: ID;
  projectId: ID;
  name: string;
  url: string;
  crawlFrequency: 'MANUAL' | 'DAILY' | 'WEEKLY' | string;
  status: Status | string;
  description?: string;
  lastCrawledAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface PolicySourceForm {
  name: string;
  url: string;
  crawlFrequency: string;
  description?: string;
}

export interface PolicyCrawlTask {
  taskId: ID;
  projectId: ID;
  sourceId?: ID;
  sourceName?: string;
  status: Status | string;
  progress?: number;
  fetchedCount: number;
  indexedCount: number;
  message?: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
}

export interface PolicyArticle {
  articleId: ID;
  projectId: ID;
  sourceId: ID;
  title: string;
  url: string;
  summary: string;
  publishDate?: string;
  category?: string;
  indexStatus: Status | string;
  createdAt: string;
  updatedAt: string;
}

export interface AuditLog {
  id: ID;
  projectId?: ID;
  operatorId?: ID;
  operatorName?: string;
  action: string;
  module?: string;
  objectType?: string;
  objectId?: ID;
  targetType?: string;
  targetId?: ID;
  requestId?: string;
  result?: 'SUCCESS' | 'FAILED' | string;
  ipAddress?: string;
  ip?: string;
  detail?: string;
  createdAt: string;
}

export interface ExternalCallLog {
  id: ID;
  projectId?: ID;
  serviceName: string;
  callType: string;
  requestId?: string;
  requestSummary?: string;
  responseSummary?: string;
  status: string;
  costMs?: number;
  errorMessage?: string;
  createdAt: string;
}
