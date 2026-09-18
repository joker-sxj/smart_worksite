<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import AppUpload from '../../components/common/AppUpload.vue';
import AppTable from '../../components/common/AppTable.vue';
import JsonViewer from '../../components/common/JsonViewer.vue';
import TaskProgress from '../../components/common/TaskProgress.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import { fetchReviewFieldSchema, fetchReviewRecord, fetchReviewRecords, fetchReviewTemplates, saveReviewFieldSchema, submitReviewRecord, updateReviewIssue } from '../../api/review';
import { fetchTaskStages } from '../../api/task';
import { fetchKnowledgeBases, fetchKnowledgeDocuments } from '../../api/knowledge';
import { useProjectStore } from '../../stores/project';
import { useUserStore } from '../../stores/user';
import type { ID, KnowledgeDocument, ReviewField, ReviewFieldSchema, ReviewRecord, ReviewTemplate, TaskStageLog } from '../../api/types';
import { canUpdateReviewIssues, progressFromReviewState, reviewStorageKey, shouldPollReviewRecord } from './reviewPolling';
import { deriveManualConfirmationItems, isCurrentReviewRequest, reviewRuleResults, selectRestoredReviewRecord } from './reviewResultViewModel';
import { exceedsReviewReferenceLimit } from './reviewSubmission';

const router = useRouter();
const projectStore = useProjectStore();
const userStore = useUserStore();
const loading = ref(false);
const submitting = ref(false);
const templateError = ref('');
const submitError = ref('');
const resultNotice = ref('');
const stageNotice = ref('');
const templates = ref<ReviewTemplate[]>([]);
const selectedTemplateId = ref<ID>('');
const file = ref<File | null>(null);
const referenceFiles = ref<File[]>([]);
const referenceDocuments = ref<KnowledgeDocument[]>([]);
const selectedReferenceDocumentIds = ref<ID[]>([]);
const fieldSchema = ref<ReviewFieldSchema | null>(null);
const fieldValues = ref<Record<string, unknown>>({});
const schemaDraft = ref<ReviewField[]>([]);
const savingSchema = ref(false);
const schemaError = ref('');
const schemaLoading = ref(false);
const currentRecord = ref<ReviewRecord | null>(null);
const recentRecords = ref<ReviewRecord[]>([]);
const selectedRecordId = ref<ID>('');
const submittedInfo = ref<{ recordId?: ID; taskId?: ID; status?: string } | null>(null);
const logs = ref<TaskStageLog[]>([]);
const updatingIssueId = ref('');
let recordPollTimer: ReturnType<typeof setTimeout> | null = null;
let recordLoadGeneration = 0;
let restoreGeneration = 0;
const RECORD_POLL_INTERVAL_MS = 2000;
const canManageReview = computed(() => userStore.hasPermission('review:manage'));
const reviewManageTip = '当前账号没有合规审查管理权限';
const canSubmit = computed(() => Boolean(canManageReview.value && templates.value.length && selectedTemplateId.value && file.value && !submitting.value && !schemaLoading.value && !schemaError.value));
const ruleResults = computed(() => reviewRuleResults(currentRecord.value));
const manualConfirmationItems = computed(() => deriveManualConfirmationItems({
  issues: currentRecord.value?.issues,
  ruleResults: ruleResults.value
}));
const issueStatusOptions = [
  { label: '待处理', value: 'OPEN' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '已解决', value: 'RESOLVED' },
  { label: '已忽略', value: 'IGNORED' }
];
const reviewSteps = [
  { title: '先准备审查标准', desc: '到模板管理上传审查模板，系统按模板判断文件是否合规。' },
  { title: '再上传待审文件', desc: '上传施工方案、合同、制度等 Word 或 PDF 文件。' },
  { title: '最后查看结果', desc: '查看问题位置、修改建议、处理状态和 JSON 结果。' }
];

function t(text: string) { return text; }
function goTemplates() {
  router.push({ path: '/templates', query: { category: 'REVIEW', action: 'upload' } });
}
function progressOf(record: ReviewRecord) { return progressFromReviewState(record, logs.value); }
function canUpdateIssue(record: ReviewRecord | null) { return canManageReview.value && canUpdateReviewIssues(record?.status); }

function stopRecordPolling() {
  if (recordPollTimer) clearTimeout(recordPollTimer);
  recordPollTimer = null;
}

function persistRecordId(projectId: ID, recordId: ID) {
  localStorage.setItem(reviewStorageKey(projectId), String(recordId));
}

function scheduleRecordPolling(recordId: ID, fallbackStatus?: string) {
  stopRecordPolling();
  const loadedRecord = String(currentRecord.value?.recordId || '') === String(recordId) ? currentRecord.value : null;
  if (!shouldPollReviewRecord(loadedRecord || (fallbackStatus ? { status: fallbackStatus } as ReviewRecord : null))) return;
  recordPollTimer = setTimeout(async () => {
    const loaded = await loadRecord(recordId);
    if (loaded === 'stale') return;
    const nextRecord = String(currentRecord.value?.recordId || '') === String(recordId) ? currentRecord.value : null;
    if (shouldPollReviewRecord(nextRecord || (loaded === 'failed' && fallbackStatus ? { status: fallbackStatus } as ReviewRecord : null))) {
      scheduleRecordPolling(recordId, fallbackStatus);
    }
  }, RECORD_POLL_INTERVAL_MS);
}

async function restoreProjectRecord(projectId: ID) {
  const generation = ++restoreGeneration;
  const storageKey = reviewStorageKey(projectId);
  const persistedRecordId = localStorage.getItem(storageKey);
  try {
    const page = await fetchReviewRecords({ projectId, pageNo: 1, pageSize: 50 });
    if (!isCurrentReviewRequest(generation, restoreGeneration, projectId, projectStore.currentProject?.projectId)) return;
    recentRecords.value = page.records;
    const target = selectRestoredReviewRecord(page.records, persistedRecordId);
    if (!target) {
      selectedRecordId.value = '';
      if (persistedRecordId) localStorage.removeItem(storageKey);
      return;
    }
    if (persistedRecordId && String(target.recordId) !== String(persistedRecordId)) {
      localStorage.removeItem(storageKey);
    }
    await openRecord(target.recordId, target.status);
  } catch (err) {
    if (!isCurrentReviewRequest(generation, restoreGeneration, projectId, projectStore.currentProject?.projectId)) return;
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    resultNotice.value = `${t('最近审查记录加载失败，请稍后重试。')}${detail}`;
  }
}

async function openRecord(recordId: ID, fallbackStatus?: string) {
  stopRecordPolling();
  selectedRecordId.value = recordId;
  currentRecord.value = null;
  const loaded = await loadRecord(recordId);
  if (loaded !== 'stale') scheduleRecordPolling(recordId, fallbackStatus);
}


async function loadTemplates() {
  loading.value = true;
  templateError.value = '';
  try {
    if (!projectStore.currentProject) await projectStore.fetchProjects();
    const projectId = projectStore.currentProject?.projectId;
    templates.value = projectId ? await fetchReviewTemplates(projectId) : [];
    if (!selectedTemplateId.value && templates.value[0]) selectedTemplateId.value = templates.value[0].templateId;
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    templateError.value = `${t('审查模板加载失败，请检查后端模板接口。')}${detail}`;
  } finally {
    loading.value = false;
  }
}

async function loadFieldSchema() {
  const projectId = projectStore.currentProject?.projectId;
  if (!projectId || !selectedTemplateId.value) { fieldSchema.value = null; return; }
  schemaLoading.value = true; schemaError.value = '';
  try { fieldSchema.value = await fetchReviewFieldSchema(projectId, selectedTemplateId.value); schemaDraft.value = fieldSchema.value.fields.map((item) => ({ ...item, options: [...item.options], validation: { ...item.validation } })); }
  catch (err) { fieldSchema.value = null; schemaError.value = err instanceof Error ? err.message : '审查字段配置加载失败，已阻止提交'; }
  finally { schemaLoading.value = false; }
}

function setValidation(field: ReviewField, key: string, value: unknown) {
  field.validation = { ...field.validation, [key]: value === '' ? undefined : value };
}

function addSchemaField() {
  schemaDraft.value.push({ key: '', label: '', stage: 'INPUT', type: 'STRING', required: false, options: [], sort: schemaDraft.value.length + 1, validation: {} });
}

async function persistSchema() {
  const projectId = projectStore.currentProject?.projectId;
  if (!projectId || !selectedTemplateId.value) return;
  savingSchema.value = true;
  try { fieldSchema.value = await saveReviewFieldSchema(projectId, selectedTemplateId.value, schemaDraft.value); ElMessage.success('审查字段配置已保存为新版本'); }
  catch (err) { ElMessage.error(err instanceof Error ? err.message : '审查字段配置保存失败'); }
  finally { savingSchema.value = false; }
}

async function loadReferenceDocuments() {
  const projectId = projectStore.currentProject?.projectId;
  if (!projectId) { referenceDocuments.value = []; return; }
  const bases = await fetchKnowledgeBases(projectId, { pageNo: 1, pageSize: 100 });
  const pages = await Promise.all(bases.map((base) => fetchKnowledgeDocuments(base.knowledgeBaseId, { pageNo: 1, pageSize: 100 })));
  referenceDocuments.value = pages.flatMap((page) => page.records).filter((document) => String(document.indexStatus).toUpperCase() === 'SUCCESS');
}

async function loadStages(taskId?: ID, recordGeneration?: number) {
  stageNotice.value = '';
  if (!taskId) {
    if (recordGeneration == null || recordGeneration === recordLoadGeneration) logs.value = [];
    return;
  }
  try {
    const stages = await fetchTaskStages(taskId);
    if (recordGeneration == null || recordGeneration === recordLoadGeneration) logs.value = stages;
  } catch (err) {
    if (recordGeneration != null && recordGeneration !== recordLoadGeneration) return;
    logs.value = [];
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    stageNotice.value = `${t('阶段日志暂不可用')}${detail}`;
  }
}

async function loadRecord(recordId: ID, taskId?: ID, status?: string): Promise<'loaded' | 'failed' | 'stale'> {
  const generation = ++recordLoadGeneration;
  const expectedProjectId = projectStore.currentProject?.projectId;
  resultNotice.value = '';
  submittedInfo.value = { recordId, taskId, status };
  try {
    const record = await fetchReviewRecord(recordId);
    if (!isCurrentReviewRequest(generation, recordLoadGeneration, expectedProjectId, projectStore.currentProject?.projectId)
      || (expectedProjectId && String(record.projectId) !== String(expectedProjectId))) return 'stale';
    currentRecord.value = record;
    selectedRecordId.value = record.recordId;
    const recentIndex = recentRecords.value.findIndex((item) => String(item.recordId) === String(record.recordId));
    if (recentIndex >= 0) recentRecords.value.splice(recentIndex, 1, record);
    else recentRecords.value = [record, ...recentRecords.value].slice(0, 50);
    submittedInfo.value = { recordId: record.recordId, taskId: record.taskId, status: record.status };
    await loadStages(record.taskId || taskId, generation);
    if (generation !== recordLoadGeneration) return 'stale';
    const projectId = record.projectId || projectStore.currentProject?.projectId;
    if (projectId) persistRecordId(projectId, record.recordId);
    return 'loaded';
  } catch (err) {
    if (generation !== recordLoadGeneration) return 'stale';
    await loadStages(taskId, generation);
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    resultNotice.value = `${t('审查结果暂不可用，系统会继续刷新；也可稍后重新打开该记录。')}${detail}`;
    return 'failed';
  }
}

async function submit() {
  if (!canManageReview.value) return ElMessage.warning(reviewManageTip);
  submitError.value = '';
  if (!templates.value.length) return ElMessage.warning(t('当前项目暂无审查模板，请先到模板中心上传审查模板。'));
  if (!selectedTemplateId.value) return ElMessage.warning(t('请选择审查模板'));
  if (!file.value) return ElMessage.warning(t('请先选择审查文件'));
  if (exceedsReviewReferenceLimit(selectedReferenceDocumentIds.value, referenceFiles.value)) {
    return ElMessage.warning(t('知识文档和临时参考文件合计不能超过 20 项'));
  }
  const projectId = projectStore.currentProject?.projectId;
  if (!projectId) return ElMessage.warning(t('请先选择项目'));
  const missing = (fieldSchema.value?.fields || []).filter((item) => item.stage === 'INPUT' && item.required && (fieldValues.value[item.key] == null || fieldValues.value[item.key] === '')).map((item) => item.label || item.key);
  if (missing.length) return ElMessage.warning(`请填写必填审查字段：${missing.join('、')}`);
  submitting.value = true;
  resultNotice.value = '';
  stageNotice.value = '';
  try {
    const result = await submitReviewRecord({
      projectId, templateId: selectedTemplateId.value, file: file.value,
      referenceDocumentIds: selectedReferenceDocumentIds.value,
      referenceFiles: referenceFiles.value, fieldValues: fieldValues.value, schemaVersion: fieldSchema.value?.version
    });
    submittedInfo.value = result;
    ElMessage.success(t('审查任务已提交'));
    persistRecordId(projectId, result.recordId);
    selectedRecordId.value = result.recordId;
    currentRecord.value = null;
    const loaded = await loadRecord(result.recordId, result.taskId, result.status);
    if (loaded !== 'stale') scheduleRecordPolling(result.recordId, result.status);
  } catch (err) {
    submitError.value = err instanceof Error ? err.message : t('审查提交失败，请检查后端审查接口。');
  } finally { submitting.value = false; }
}

async function changeIssueStatus(issueId: string, status: string, comment?: string) {
  if (!canManageReview.value) return ElMessage.warning(reviewManageTip);
  if (!currentRecord.value) return;
  if (!canUpdateIssue(currentRecord.value)) return ElMessage.warning('只有已完成的审查记录才能更新问题状态');
  updatingIssueId.value = issueId;
  try {
    currentRecord.value = await updateReviewIssue(currentRecord.value.recordId, issueId, { status, comment });
    ElMessage.success('问题状态已更新');
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '问题状态更新失败，请检查后端审查接口。');
  } finally {
    updatingIssueId.value = '';
  }
}

onMounted(async () => {
  await loadTemplates();
  await loadReferenceDocuments();
  const projectId = projectStore.currentProject?.projectId;
  if (projectId && String(projectStore.currentProject?.projectId || '') === String(projectId)) {
    await restoreProjectRecord(projectId);
  }
});
watch(() => projectStore.currentProject?.projectId, async (projectId, previousProjectId) => {
  if (!projectId || String(projectId) === String(previousProjectId || '')) return;
  stopRecordPolling();
  recordLoadGeneration += 1;
  restoreGeneration += 1;
  selectedTemplateId.value = '';
  currentRecord.value = null;
  recentRecords.value = [];
  selectedRecordId.value = '';
  selectedReferenceDocumentIds.value = [];
  referenceFiles.value = [];
  submittedInfo.value = null;
  await loadTemplates();
  await loadReferenceDocuments();
  if (String(projectStore.currentProject?.projectId || '') === String(projectId)) {
    await restoreProjectRecord(projectId);
  }
});
watch(selectedTemplateId, loadFieldSchema, { immediate: true });
onUnmounted(() => {
  stopRecordPolling();
  recordLoadGeneration += 1;
  restoreGeneration += 1;
});
</script>

<template>
  <div class="page" v-loading="loading">
    <el-alert v-if="templateError" :title="templateError" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
    <el-alert v-if="submitError" :title="submitError" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
    <el-alert v-if="resultNotice" :title="resultNotice" type="info" show-icon :closable="false" style="margin-bottom: 12px" />
    <el-alert v-if="stageNotice" :title="stageNotice" type="warning" show-icon :closable="false" style="margin-bottom: 12px" />
    <el-alert v-if="schemaError" :title="`审查字段配置加载失败：${schemaError}`" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
    <el-alert title="审查任务在服务端继续执行，处理中可以离开本页；返回后系统会恢复最近记录并继续刷新。" type="info" show-icon :closable="false" style="margin-bottom: 12px" />
    <div class="page-header">
      <div>
        <h2 class="page-title">{{ t('合规审查') }}</h2>
        <p class="page-desc">{{ t('按“准备模板 → 上传文件 → 发起审查 → 处理问题”的顺序使用。') }}</p>
      </div>
      <el-button type="primary" plain @click="goTemplates">{{ t('上传审查模板') }}</el-button>
    </div>
    <el-card v-if="recentRecords.length" class="work-card recent-records">
      <div class="record-picker">
        <div><strong>最近审查记录</strong><p>可重新打开当前项目已有结果</p></div>
        <el-select v-model="selectedRecordId" style="width: min(100%, 420px)" @change="(recordId: ID) => openRecord(recordId, recentRecords.find((item) => String(item.recordId) === String(recordId))?.status)">
          <el-option v-for="item in recentRecords" :key="item.recordId" :value="item.recordId" :label="`#${item.recordId} · ${item.templateName || '审查记录'} · ${item.status}`" />
        </el-select>
      </div>
    </el-card>
    <div class="review-guide">
      <div v-for="(item, index) in reviewSteps" :key="item.title" class="guide-step" :class="{ active: !templates.length && index === 0 }">
        <span>{{ index + 1 }}</span>
        <strong>{{ item.title }}</strong>
        <p>{{ item.desc }}</p>
      </div>
    </div>
    <el-card class="work-card">
      <template #header><strong>{{ t('上传文件并发起审查') }}</strong></template>
      <el-alert
        v-if="!loading && !templates.length"
        title="当前不能发起审查：还没有审查模板"
        description="请先上传审查模板。模板就是审查规则或标准文件；没有模板，系统不知道按什么标准检查合同或方案。"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
      />
      <el-empty v-if="!loading && !templates.length" :description="t('第一步：去模板管理上传“审查模板”，上传完成后回到本页。')">
        <el-button type="primary" @click="goTemplates">{{ t('去上传审查模板') }}</el-button>
      </el-empty>
      <template v-else>
        <el-form inline>
          <el-form-item :label="t('1. 审查模板')">
            <el-select v-model="selectedTemplateId" style="width: 260px" :placeholder="t('请选择模板')">
              <el-option v-for="item in templates" :key="item.templateId" :label="item.templateName" :value="item.templateId" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button @click="goTemplates">{{ t('上传模板') }}</el-button>
          </el-form-item>
          <el-form-item>
            <el-button v-if="canManageReview" type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">{{ t('3. 发起审查') }}</el-button>
          </el-form-item>
        </el-form>
        <el-form v-if="fieldSchema?.fields.some((item) => item.stage === 'INPUT')" label-position="top" class="review-fields">
          <el-form-item v-for="item in fieldSchema.fields.filter((field) => field.stage === 'INPUT')" :key="item.key" :label="`${item.label || item.key}${item.required ? ' *' : ''}`">
            <el-select v-if="item.type === 'ENUM'" v-model="fieldValues[item.key]" style="width: 100%"><el-option v-for="option in item.options" :key="option" :label="option" :value="option" /></el-select>
            <el-input-number v-else-if="item.type === 'NUMBER'" v-model="fieldValues[item.key] as number" style="width: 100%" />
            <el-switch v-else-if="item.type === 'BOOLEAN'" v-model="fieldValues[item.key] as boolean" />
            <el-date-picker v-else-if="item.type === 'DATE'" v-model="fieldValues[item.key]" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            <el-input v-else v-model="fieldValues[item.key] as string" :type="item.type === 'TEXT' ? 'textarea' : 'text'" />
          </el-form-item>
        </el-form>
        <el-collapse v-if="canManageReview" class="schema-editor">
          <el-collapse-item title="配置独立审查字段（保存后生成新版本）">
            <div v-for="(item, index) in schemaDraft" :key="index" class="schema-row">
              <el-input v-model="item.key" placeholder="稳定 key" /><el-input v-model="item.label" placeholder="字段名称" />
              <el-select v-model="item.stage"><el-option label="审查前输入" value="INPUT" /><el-option label="文档抽取" value="DOCUMENT" /><el-option label="审查结果" value="RESULT" /></el-select>
              <el-select v-model="item.type"><el-option v-for="type in ['STRING','TEXT','NUMBER','BOOLEAN','DATE','ENUM']" :key="type" :label="type" :value="type" /></el-select>
              <el-checkbox v-model="item.required">必填</el-checkbox><el-input-number v-model="item.sort" :min="0" />
              <el-button type="danger" plain @click="schemaDraft.splice(index, 1)">删除</el-button>
              <el-select v-if="item.type === 'ENUM'" v-model="item.options" multiple filterable allow-create default-first-option placeholder="枚举选项" class="wide-field" />
              <div class="validation-fields wide-field">
                <el-input-number v-if="item.type === 'NUMBER'" :model-value="item.validation.min as number" placeholder="最小值" @update:model-value="setValidation(item, 'min', $event)" />
                <el-input-number v-if="item.type === 'NUMBER'" :model-value="item.validation.max as number" placeholder="最大值" @update:model-value="setValidation(item, 'max', $event)" />
                <el-input-number v-else :model-value="item.validation.maxLength as number" placeholder="最大长度" @update:model-value="setValidation(item, 'maxLength', $event)" />
                <el-input :model-value="item.validation.pattern as string" placeholder="正则格式（可选）" @update:model-value="setValidation(item, 'pattern', $event)" />
              </div>
            </div>
            <div class="schema-actions"><el-button @click="addSchemaField">添加字段</el-button><el-button type="primary" :loading="savingSchema" @click="persistSchema">保存新版本</el-button></div>
          </el-collapse-item>
        </el-collapse>
        <div class="upload-title required-label">2. 上传待审文件</div>
        <AppUpload v-if="canManageReview" :model-value="file ? [file] : []" accept=".doc,.docx,.pdf" :max-size-mb="100" :multiple="false" :uploading="submitting" @update:model-value="file = $event[0] || null" />
        <p class="upload-tip">支持 Word、PDF。选择模板和文件后，点击“发起审查”。</p>
        <div class="reference-panel">
          <div class="upload-title">3. 选择参考资料（可选，最多 20 项）</div>
          <el-select v-model="selectedReferenceDocumentIds" multiple filterable collapse-tags style="width: 100%" placeholder="选择当前项目已入库的知识文档">
            <el-option v-for="item in referenceDocuments" :key="item.documentId" :label="item.title" :value="item.documentId">
              <span>{{ item.title }}</span><small class="reference-meta">{{ item.fileExt || item.contentType || '文档' }} · 已入库</small>
            </el-option>
          </el-select>
          <div class="upload-title optional-upload">或上传本次审查使用的临时参考文件</div>
          <AppUpload v-if="canManageReview" :model-value="referenceFiles" accept=".doc,.docx,.pdf" :max-size-mb="100" :multiple="true" :uploading="submitting" @update:model-value="referenceFiles = $event.slice(0, 10)" />
          <p class="upload-tip">参考资料只作为审查依据，不会被当成待审文件中的问题。临时文件最多 10 个。</p>
        </div>
      </template>
    </el-card>
    <el-card v-if="submittedInfo && !currentRecord" class="work-card"><h3 class="panel-title">{{ t('已提交任务') }}</h3><p>recordId: {{ submittedInfo.recordId || '-' }}</p><p>taskId: {{ submittedInfo.taskId || '-' }}</p><p>status: {{ submittedInfo.status || '-' }}</p></el-card>
    <EmptyState v-if="!loading && !resultNotice && !currentRecord && !submittedInfo" :description="t('暂无审查记录，请上传文件后发起审查。')" />
    <template v-else-if="currentRecord">
      <el-card class="work-card">
        <h3 class="panel-title">{{ t('审查进度') }}</h3>
        <p class="template-snapshot">审查模板：{{ currentRecord.templateName || `ID ${currentRecord.templateId}` }} · 版本 {{ currentRecord.templateVersion || '未记录' }}</p>
        <el-alert v-if="currentRecord.status === 'FAILED'" :title="currentRecord.errorMessage || t('审查失败，未生成结果。')" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
        <TaskProgress :percentage="progressOf(currentRecord)" :status="currentRecord.status" :logs="logs" />
      </el-card>
      <div class="two-col">
        <el-card class="work-card">
          <h3 class="panel-title">{{ t('明确问题') }}</h3>
          <AppTable :data="currentRecord.issues || []" :columns="[{ prop: 'severity', label: t('严重程度'), width: 90 }, { prop: 'location', label: t('问题定位') }, { prop: 'ruleName', label: t('规则名称') }, { prop: 'description', label: t('问题描述') }, { prop: 'suggestion', label: t('修改建议') }]">
            <template #empty><EmptyState :description="currentRecord.status === 'FAILED' ? t('审查失败，未生成明确问题。') : t('暂无明确问题。')" /></template>
            <el-table-column :label="t('问题状态')" width="140"><template #default="{ row }"><StatusTag :status="row.status || 'OPEN'" /></template></el-table-column>
            <el-table-column :label="t('处理')" width="180"><template #default="{ row }"><el-select :model-value="row.status || 'OPEN'" size="small" :disabled="!canUpdateIssue(currentRecord)" :loading="updatingIssueId === row.issueId" @change="(value: string) => changeIssueStatus(row.issueId, value, row.comment)"><el-option v-for="item in issueStatusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></template></el-table-column>
          </AppTable>
        </el-card>
        <JsonViewer :value="currentRecord" :title="t('审查 JSON 结果')" />
      </div>
      <el-card v-if="manualConfirmationItems.length" class="work-card manual-confirmation-card">
        <h3 class="panel-title">待人工确认</h3>
        <el-alert title="以下规则未形成可处理的明确问题，不会调用问题状态更新接口。" type="warning" show-icon :closable="false" />
        <el-collapse class="manual-confirmation-list">
          <el-collapse-item v-for="item in manualConfirmationItems" :key="item.ruleId" :title="`${item.ruleId} · ${item.ruleName}`">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="模型判定">{{ item.decision }}</el-descriptions-item>
              <el-descriptions-item label="置信度">{{ item.confidence == null ? '-' : `${Math.round(item.confidence * 100)}%` }}</el-descriptions-item>
              <el-descriptions-item label="异常原因" :span="2">{{ item.reason }}</el-descriptions-item>
              <el-descriptions-item label="证据摘要" :span="2">
                <ul v-if="item.evidence.length" class="evidence-summary"><li v-for="evidence in item.evidence" :key="evidence">{{ evidence }}</li></ul>
                <span v-else>未返回可用证据</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-collapse-item>
        </el-collapse>
      </el-card>
      <el-card v-if="currentRecord.references?.length" class="work-card evidence-card">
        <h3 class="panel-title">审查依据</h3>
        <div class="reference-chips">
          <el-tag v-for="item in currentRecord.references" :key="String(item.id)" effect="plain">{{ item.sourceName }}</el-tag>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.review-guide {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.guide-step {
  padding: 16px;
  border: 1px solid var(--sw-border);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.04);
}
.guide-step.active {
  border-color: var(--sw-orange);
  background: #fffbeb;
}
.guide-step span {
  width: 28px;
  height: 28px;
  display: inline-grid;
  place-items: center;
  margin-bottom: 10px;
  border-radius: 999px;
  color: #fff;
  background: var(--sw-primary);
  font-weight: 800;
}
.guide-step.active span { background: var(--sw-orange); }
.guide-step strong { display: block; margin-bottom: 6px; }
.guide-step p { margin: 0; color: var(--sw-muted); line-height: 1.6; }
.upload-title { margin: 4px 0 10px; font-weight: 700; }
.upload-tip { margin: 10px 0 0; color: var(--sw-muted); font-size: 13px; }
.reference-panel { margin-top: 22px; padding-top: 18px; border-top: 1px dashed var(--sw-border); }
.optional-upload { margin-top: 16px; }
.reference-meta { float: right; margin-left: 20px; color: var(--sw-muted); }
.reference-chips { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.template-snapshot { margin: -4px 0 12px; color: var(--sw-muted); font-size: 13px; }
.evidence-card { margin-top: 16px; }
.manual-confirmation-card { margin-top: 16px; border-color: #f3c56b; }
.manual-confirmation-list { margin-top: 14px; }
.evidence-summary { margin: 0; padding-left: 18px; }
.record-picker { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.record-picker p { margin: 4px 0 0; color: var(--sw-muted); font-size: 13px; }
.recent-records { margin-bottom: 16px; }
.schema-editor { margin: 18px 0; }
.schema-row { display: grid; grid-template-columns: 1.2fr 1.2fr 1fr 1fr auto auto auto; gap: 8px; margin-bottom: 10px; align-items: center; }
.wide-field { grid-column: 1 / -1; width: 100%; }
.validation-fields { display: flex; gap: 8px; }
.schema-actions { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 900px) {
  .review-guide { grid-template-columns: 1fr; }
  .schema-row { grid-template-columns: 1fr; }
  .record-picker { align-items: stretch; flex-direction: column; }
}
</style>
