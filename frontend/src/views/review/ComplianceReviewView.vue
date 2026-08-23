<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import AppUpload from '../../components/common/AppUpload.vue';
import AppTable from '../../components/common/AppTable.vue';
import JsonViewer from '../../components/common/JsonViewer.vue';
import TaskProgress from '../../components/common/TaskProgress.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import { fetchReviewRecord, fetchReviewTemplates, submitReviewRecord, updateReviewIssue } from '../../api/review';
import { fetchTaskStages } from '../../api/task';
import { useProjectStore } from '../../stores/project';
import { useUserStore } from '../../stores/user';
import type { ID, ReviewRecord, ReviewTemplate, TaskStageLog } from '../../api/types';

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
const currentRecord = ref<ReviewRecord | null>(null);
const submittedInfo = ref<{ recordId?: ID; taskId?: ID; status?: string } | null>(null);
const logs = ref<TaskStageLog[]>([]);
const updatingIssueId = ref('');
const canManageReview = computed(() => userStore.hasPermission('review:manage'));
const reviewManageTip = '当前账号没有合规审查管理权限';
const canSubmit = computed(() => Boolean(canManageReview.value && templates.value.length && selectedTemplateId.value && file.value && !submitting.value));
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
function progressOf(record: ReviewRecord) { return ['SUCCESS', 'COMPLETED', 'FAILED', 'ARCHIVED'].includes(String(record.status)) ? 100 : 60; }
function canUpdateIssue(record: ReviewRecord | null) { return canManageReview.value && record?.status === 'COMPLETED'; }

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

async function loadStages(taskId?: ID) {
  stageNotice.value = '';
  if (!taskId) { logs.value = []; return; }
  try { logs.value = await fetchTaskStages(taskId); }
  catch (err) { logs.value = []; const detail = err instanceof Error && err.message ? ` ${err.message}` : ''; stageNotice.value = `${t('阶段日志暂不可用')}${detail}`; }
}

async function loadRecord(recordId: ID, taskId?: ID, status?: string) {
  resultNotice.value = '';
  submittedInfo.value = { recordId, taskId, status };
  try {
    currentRecord.value = await fetchReviewRecord(recordId);
    submittedInfo.value = { recordId: currentRecord.value.recordId, taskId: currentRecord.value.taskId, status: currentRecord.value.status };
    await loadStages(currentRecord.value.taskId || taskId);
  } catch (err) {
    currentRecord.value = null;
    await loadStages(taskId);
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    resultNotice.value = `${t('审查任务已提交，但结果接口暂不可用，请稍后刷新或联系后端确认。')}${detail}`;
  }
}

async function submit() {
  if (!canManageReview.value) return ElMessage.warning(reviewManageTip);
  submitError.value = '';
  if (!templates.value.length) return ElMessage.warning(t('当前项目暂无审查模板，请先到模板中心上传审查模板。'));
  if (!selectedTemplateId.value) return ElMessage.warning(t('请选择审查模板'));
  if (!file.value) return ElMessage.warning(t('请先选择审查文件'));
  const projectId = projectStore.currentProject?.projectId;
  if (!projectId) return ElMessage.warning(t('请先选择项目'));
  submitting.value = true;
  resultNotice.value = '';
  stageNotice.value = '';
  try {
    const result = await submitReviewRecord({ projectId, templateId: selectedTemplateId.value, file: file.value });
    submittedInfo.value = result;
    ElMessage.success(t('审查任务已提交'));
    await loadRecord(result.recordId, result.taskId, result.status);
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

onMounted(loadTemplates);
</script>

<template>
  <div class="page" v-loading="loading">
    <el-alert v-if="templateError" :title="templateError" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
    <el-alert v-if="submitError" :title="submitError" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
    <el-alert v-if="resultNotice" :title="resultNotice" type="info" show-icon :closable="false" style="margin-bottom: 12px" />
    <el-alert v-if="stageNotice" :title="stageNotice" type="warning" show-icon :closable="false" style="margin-bottom: 12px" />
    <div class="page-header">
      <div>
        <h2 class="page-title">{{ t('合规审查') }}</h2>
        <p class="page-desc">{{ t('按“准备模板 → 上传文件 → 发起审查 → 处理问题”的顺序使用。') }}</p>
      </div>
      <el-button type="primary" plain @click="goTemplates">{{ t('上传审查模板') }}</el-button>
    </div>
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
        <div class="upload-title required-label">2. 上传待审文件</div>
        <AppUpload v-if="canManageReview" :model-value="file ? [file] : []" accept=".doc,.docx,.pdf" :max-size-mb="100" :multiple="false" :uploading="submitting" @update:model-value="file = $event[0] || null" />
        <p class="upload-tip">支持 Word、PDF。选择模板和文件后，点击“发起审查”。</p>
      </template>
    </el-card>
    <el-card v-if="submittedInfo && !currentRecord" class="work-card"><h3 class="panel-title">{{ t('已提交任务') }}</h3><p>recordId: {{ submittedInfo.recordId || '-' }}</p><p>taskId: {{ submittedInfo.taskId || '-' }}</p><p>status: {{ submittedInfo.status || '-' }}</p></el-card>
    <EmptyState v-if="!loading && !resultNotice && !currentRecord && !submittedInfo" :description="t('暂无审查记录，请上传文件后发起审查。')" />
    <template v-else-if="currentRecord"><el-card class="work-card"><h3 class="panel-title">{{ t('审查进度') }}</h3><TaskProgress :percentage="progressOf(currentRecord)" :status="currentRecord.status" :logs="logs" /></el-card><div class="two-col"><el-card class="work-card"><h3 class="panel-title">{{ t('问题列表') }}</h3><AppTable :data="currentRecord.issues || []" :columns="[{ prop: 'severity', label: t('严重程度'), width: 90 }, { prop: 'location', label: t('问题定位') }, { prop: 'ruleName', label: t('规则名称') }, { prop: 'description', label: t('问题描述') }, { prop: 'suggestion', label: t('修改建议') }]"><template #empty><EmptyState :description="t('暂无审查问题。')" /></template><el-table-column :label="t('问题状态')" width="140"><template #default="{ row }"><StatusTag :status="row.status || 'OPEN'" /></template></el-table-column><el-table-column :label="t('处理')" width="180"><template #default="{ row }"><el-select :model-value="row.status || 'OPEN'" size="small" :disabled="!canUpdateIssue(currentRecord)" :loading="updatingIssueId === row.issueId" @change="(value: string) => changeIssueStatus(row.issueId, value, row.comment)"><el-option v-for="item in issueStatusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></template></el-table-column></AppTable></el-card><JsonViewer :value="currentRecord" :title="t('审查 JSON 结果')" /></div></template>
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
@media (max-width: 900px) {
  .review-guide { grid-template-columns: 1fr; }
}
</style>
