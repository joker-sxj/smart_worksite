<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import JsonViewer from '../../components/common/JsonViewer.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import TaskProgress from '../../components/common/TaskProgress.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import { downloadReport, fetchReportDetail, fetchReportVariables, regenerateReport } from '../../api/report';
import { fetchTaskStages } from '../../api/task';
import type { ReportItem, ReportVariableItem, TaskStageLog } from '../../api/types';

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const downloading = ref(false);
const regenerating = ref(false);
const error = ref('');
const notFound = ref(false);
const report = ref<ReportItem | null>(null);
const logs = ref<TaskStageLog[]>([]);
const variables = ref<ReportVariableItem[]>([]);
let refreshTimer: number | undefined;

const downloadableStatuses = new Set(['COMPLETED', 'PARTIAL_SUCCESS']);
const regeneratableStatuses = new Set(['COMPLETED', 'PARTIAL_SUCCESS', 'FAILED', 'ARCHIVED']);

function normalizeStatus(status?: string) {
  return (status || '').toUpperCase();
}

function canDownloadReport(item: ReportItem) {
  return downloadableStatuses.has(normalizeStatus(item.status));
}

function canRegenerateReport(item: ReportItem) {
  return regeneratableStatuses.has(normalizeStatus(item.status));
}

function isNotFoundError(err: unknown) {
  const message = err instanceof Error ? err.message : String(err || '');
  return message.includes('404') || message.includes('不存在') || message.includes('not found') || message.includes('Not Found');
}

async function loadData(silent = false) {
  if (!silent) loading.value = true;
  error.value = '';
  notFound.value = false;
  try {
    report.value = await fetchReportDetail(route.params.id as string);
    const [variableRecords, stageRecords] = await Promise.all([
      fetchReportVariables(report.value.reportId),
      report.value.taskId ? fetchTaskStages(report.value.taskId) : Promise.resolve([])
    ]);
    variables.value = variableRecords;
    logs.value = stageRecords;
  } catch (err) {
    report.value = null;
    notFound.value = isNotFoundError(err);
    error.value = notFound.value ? '' : (err instanceof Error ? err.message : '报告详情加载失败');
  } finally {
    if (!silent) loading.value = false;
  }
}

function scheduleRefresh() {
  if (refreshTimer) window.clearTimeout(refreshTimer);
  if (!report.value || ['COMPLETED', 'PARTIAL_SUCCESS', 'FAILED', 'ARCHIVED'].includes(normalizeStatus(report.value.status))) return;
  refreshTimer = window.setTimeout(async () => {
    await loadData(true);
    scheduleRefresh();
  }, 3000);
}

async function handleDownload() {
  if (!report.value) return;
  if (!canDownloadReport(report.value)) {
    error.value = `当前报告状态为 ${report.value.status}，尚不能下载`;
    return;
  }
  downloading.value = true;
  error.value = '';
  try {
    await downloadReport(report.value.reportId, 'WORD', `${report.value.reportName}.docx`);
  } catch (err) {
    error.value = err instanceof Error ? err.message : '报告下载失败，请检查后端下载地址是否可用';
  } finally {
    downloading.value = false;
  }
}

async function handleRegenerate() {
  if (!report.value) return;
  if (!canRegenerateReport(report.value)) {
    error.value = `当前报告状态为 ${report.value.status}，不能重复发起生成`;
    return;
  }
  regenerating.value = true;
  error.value = '';
  try {
    const result = await regenerateReport(report.value.reportId);
    if (result.reportId && String(result.reportId) !== String(report.value.reportId)) {
      await router.replace(`/report/${result.reportId}`);
      await loadData();
      scheduleRefresh();
      return;
    }
    await loadData();
    scheduleRefresh();
  } catch (err) {
    error.value = err instanceof Error ? err.message : '重新生成失败';
  } finally {
    regenerating.value = false;
  }
}

onMounted(async () => {
  await loadData();
  scheduleRefresh();
});

onBeforeUnmount(() => {
  if (refreshTimer) window.clearTimeout(refreshTimer);
});
</script>

<template>
  <div class="page" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <EmptyState v-if="!loading && (notFound || !report)" description="报告不存在或已删除" />
    <template v-else-if="report">
      <el-alert
        v-if="report.status === 'FAILED'"
        :title="report.errorMessage || '报告尚未生成成功'"
        type="error"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-alert
        v-if="report.status === 'PARTIAL_SUCCESS'"
        :title="report.errorMessage || '部分变量未自动生成，失败位置已写入 Word 报告'"
        description="报告仍可下载；请在 Word 中查看失败占位说明并人工补充，或点击重新生成仅重试失败变量。"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      />
      <div class="page-header">
        <div>
          <h2 class="page-title">报告详情</h2>
          <p class="page-desc">版本、状态、预览、下载和重新生成。</p>
        </div>
        <el-space>
          <el-button type="primary" plain :loading="downloading" :disabled="!canDownloadReport(report)" @click="handleDownload">下载报告</el-button>
          <el-button :loading="regenerating" :disabled="!canRegenerateReport(report)" @click="handleRegenerate">重新生成</el-button>
        </el-space>
      </div>

      <el-card class="work-card">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="报告名称">{{ report.reportName }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ report.version }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="report.status" /></el-descriptions-item>
          <el-descriptions-item v-if="report.errorMessage" label="失败原因" :span="3">{{ report.errorMessage }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card class="work-card">
        <h3 class="panel-title">报告变量</h3>
        <el-table v-if="variables.length" :data="variables" row-key="variableId" stripe>
          <el-table-column prop="sortNo" label="顺序" width="72" />
          <el-table-column prop="variableName" label="变量名" min-width="180" />
          <el-table-column prop="variableDescription" label="变量描述" min-width="260" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><StatusTag :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="生成内容" min-width="320">
            <template #default="{ row }">
              <div v-if="row.variableValue" class="variable-value">{{ row.variableValue }}</div>
              <span v-else-if="row.errorMessage" class="variable-error">{{ row.errorMessage }}</span>
              <span v-else class="variable-empty">等待生成</span>
            </template>
          </el-table-column>
          <el-table-column label="数据依据" min-width="180">
            <template #default="{ row }">
              <el-popover v-if="row.referencesJson && row.referencesJson !== '[]'" placement="left" width="520" trigger="click">
                <pre class="reference-json">{{ row.referencesJson }}</pre>
                <template #reference><el-button link type="primary">查看真实查询与数据</el-button></template>
              </el-popover>
              <span v-else class="variable-empty">无数据库引用</span>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState v-else description="暂无报告变量记录" />
      </el-card>

      <div class="two-col">
        <el-card class="work-card">
          <h3 class="panel-title">报告预览</h3>
          <p>报告完成或部分成功后可下载 Word 文件查看；部分成功报告会标明需人工补充的位置。</p>
        </el-card>
        <el-card class="work-card">
          <h3 class="panel-title">生成进度</h3>
          <TaskProgress :percentage="report.progress" :status="report.status" :logs="logs" />
        </el-card>
      </div>

      <JsonViewer :value="report" title="报告元数据" />
    </template>
  </div>
</template>

<style scoped>
.variable-value { white-space: pre-wrap; line-height: 1.65; }
.variable-error { color: var(--el-color-danger); }
.variable-empty { color: var(--sw-muted); }
.reference-json { max-height: 360px; overflow: auto; margin: 0; white-space: pre-wrap; font-size: 12px; }
</style>
