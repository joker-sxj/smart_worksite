<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import AppSearchForm from '../../components/common/AppSearchForm.vue';
import AppTable from '../../components/common/AppTable.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import { createReport, downloadReport, fetchReportTemplates, fetchReports, type ReportTemplate } from '../../api/report';
import { fetchKnowledgeBases } from '../../api/knowledge';
import { fetchDataSources } from '../../api/datasource';
import { useProjectStore } from '../../stores/project';
import { useUserStore } from '../../stores/user';
import { hasSuspiciousText } from '../../utils/textQuality';
import type { DataSourceItem, ID, KnowledgeBase, ReportItem } from '../../api/types';

const router = useRouter();
const projectStore = useProjectStore();
const userStore = useUserStore();
const loading = ref(false);
const creating = ref(false);
const dialogVisible = ref(false);
const error = ref('');
const templateWarning = ref('');
const knowledgeWarning = ref('');
const search = reactive({ keyword: '', status: '' });
const pager = reactive({ pageNo: 1, pageSize: 20, total: 0 });
const reports = ref<ReportItem[]>([]);
const templates = ref<ReportTemplate[]>([]);
const knowledgeBases = ref<KnowledgeBase[]>([]);
const dataSources = ref<DataSourceItem[]>([]);
const downloadingId = ref<ID>('');
const form = reactive<{ reportName: string; reportType: string; templateId: ID | ''; knowledgeBaseIds: ID[]; dataSourceIds: ID[] }>({
  reportName: '',
  reportType: 'SAFETY_MONTHLY',
  templateId: '',
  knowledgeBaseIds: [],
  dataSourceIds: []
});

const currentProjectId = computed(() => projectStore.currentProject?.projectId);
const canManageReport = computed(() => userStore.hasPermission('report:view'));
const templateEmpty = computed(() => !templates.value.length && !templateWarning.value);
const canCreate = computed(() => Boolean(canManageReport.value && currentProjectId.value && form.reportName.trim() && form.reportType && form.templateId && (form.knowledgeBaseIds.length || form.dataSourceIds.length)));
const downloadableStatuses = new Set(['COMPLETED']);
const statusOptions = [
  { label: '已完成', value: 'COMPLETED' },
  { label: '待生成', value: 'PENDING' },
  { label: '生成中', value: 'PROCESSING' },
  { label: '失败', value: 'FAILED' }
];

function normalizeStatus(status?: string) {
  return (status || '').toUpperCase();
}

function canDownloadReport(row: ReportItem) {
  return downloadableStatuses.has(normalizeStatus(row.status));
}

async function loadData() {
  loading.value = true;
  error.value = '';
  try {
    if (!projectStore.currentProject) await projectStore.fetchProjects();
    const page = await fetchReports({
      projectId: projectStore.currentProject?.projectId,
      keyword: search.keyword,
      status: search.status,
      pageNo: pager.pageNo,
      pageSize: pager.pageSize
    });
    reports.value = page.records;
    pager.total = page.total;
  } catch (err) {
    error.value = err instanceof Error ? err.message : '报告列表加载失败';
    reports.value = [];
    pager.total = 0;
  } finally {
    loading.value = false;
  }
}

async function openCreateDialog() {
  if (!canManageReport.value) return ElMessage.warning('当前账号没有报告创建权限');
  dialogVisible.value = true;
  templateWarning.value = '';
  knowledgeWarning.value = '';
  templates.value = [];
  knowledgeBases.value = [];
  dataSources.value = [];
  form.templateId = '';
  form.knowledgeBaseIds = [];
  form.dataSourceIds = [];
  try {
    if (!currentProjectId.value) throw new Error('请先选择项目');
    templates.value = await fetchReportTemplates(currentProjectId.value);
    if (templates.value[0]) form.templateId = templates.value[0].templateId;
    const bases = await fetchKnowledgeBases(currentProjectId.value, { status: 'ENABLED', pageNo: 1, pageSize: 100 });
    knowledgeBases.value = bases.filter((item) => String(item.status).toUpperCase() === 'ENABLED'
      && String(item.knowledgeBaseType || 'PROJECT').toUpperCase() === 'PROJECT');
    if (!knowledgeBases.value.length) {
      knowledgeWarning.value = '当前项目暂无已启用的知识库，仍可仅选择数据库数据源生成报告。';
    }
    const sourcePage = await fetchDataSources({ projectId: currentProjectId.value, status: 'ENABLED', pageNo: 1, pageSize: 100 });
    dataSources.value = sourcePage.records.filter((item) => String(item.status).toUpperCase() === 'ENABLED');
  } catch (err) {
    templateWarning.value = err instanceof Error ? err.message : '报告创建依赖数据加载失败';
  }
}

function reset() {
  search.keyword = '';
  search.status = '';
  pager.pageNo = 1;
  void loadData();
}

async function submitCreate() {
  if (!canManageReport.value) return ElMessage.warning('当前账号没有报告创建权限');
  if (!form.reportName.trim()) return ElMessage.warning('请填写报告名称');
  if (!form.reportType) return ElMessage.warning('请选择报告类型');
  if (!form.templateId) return ElMessage.warning('请选择报告模板');
  if (!form.knowledgeBaseIds.length && !form.dataSourceIds.length) return ElMessage.warning('知识库和数据源至少选择一项');
  creating.value = true;
  error.value = '';
  try {
    const result = await createReport({
      projectId: currentProjectId.value,
      reportName: form.reportName,
      reportType: form.reportType,
      templateId: form.templateId,
      knowledgeBaseIds: form.knowledgeBaseIds,
      dataSourceIds: form.dataSourceIds
    });
    if (result.status === 'FAILED') {
      ElMessage.warning('报告任务已创建，但生成失败，请进入详情查看失败原因');
    } else {
      ElMessage.success('报告创建成功');
    }
    dialogVisible.value = false;
    form.reportName = '';
    form.knowledgeBaseIds = [];
    form.dataSourceIds = [];
    await loadData();
  } catch (err) {
    error.value = err instanceof Error ? err.message : '报告创建失败，请确认后端 /api/reports 是否可用';
  } finally {
    creating.value = false;
  }
}

async function handleDownload(row: ReportItem) {
  if (!canDownloadReport(row)) return ElMessage.warning(`当前报告状态为 ${row.status}，尚不能下载`);
  downloadingId.value = row.reportId;
  error.value = '';
  try {
    await downloadReport(row.reportId, 'WORD', `${row.reportName}.docx`);
  } catch (err) {
    error.value = err instanceof Error ? err.message : '报告下载失败';
  } finally {
    downloadingId.value = '';
  }
}

function goTemplates() {
  dialogVisible.value = false;
  router.push('/templates');
}

onMounted(loadData);
</script>

<template>
  <div class="page">
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <div class="page-header">
      <div>
        <h2 class="page-title">报告管理</h2>
        <p class="page-desc">报告列表、异步生成状态、详情查看和生成结果下载。</p>
      </div>
      <el-button v-if="canManageReport" type="primary" @click="openCreateDialog">新建报告</el-button>
    </div>

    <AppSearchForm
      :model-value="search"
      :fields="[
        { prop: 'keyword', label: '关键词' },
        { prop: 'status', label: '状态', type: 'select', options: statusOptions }
      ]"
      @update:model-value="(value) => Object.assign(search, value)"
      @search="loadData"
      @reset="reset"
    />

    <el-card class="work-card">
      <AppTable
        :loading="loading"
        :data="reports"
        :error="error"
        :total="pager.total"
        :page-no="pager.pageNo"
        :page-size="pager.pageSize"
        :columns="[
          { prop: 'reportName', label: '报告名称', slot: 'reportName' },
          { prop: 'version', label: '版本' },
          { prop: 'status', label: '状态', slot: 'status' },
          { prop: 'createdBy', label: '创建人' },
          { prop: 'updatedAt', label: '更新时间' }
        ]"
        @page-change="(p, s) => { pager.pageNo = p; pager.pageSize = s; loadData(); }"
      >
        <template #empty><EmptyState description="暂无报告，可先上传报告模板后创建报告。" /></template>
        <template #reportName="{ row }">
          <span>{{ row.reportName }}</span>
          <el-tag v-if="hasSuspiciousText(row.reportName)" type="warning" size="small" style="margin-left: 6px">疑似历史乱码数据</el-tag>
        </template>
        <template #status="{ row }"><StatusTag :status="row.status" /></template>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/report/${row.reportId}`)">详情</el-button>
            <el-button link :loading="String(downloadingId) === String(row.reportId)" :disabled="!canDownloadReport(row)" @click="handleDownload(row)">下载</el-button>
          </template>
        </el-table-column>
      </AppTable>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建报告" width="560px">
      <el-alert v-if="templateWarning" :title="templateWarning" type="warning" show-icon :closable="false" style="margin-bottom: 12px" />
      <el-alert v-if="knowledgeWarning" :title="knowledgeWarning" type="warning" show-icon :closable="false" style="margin-bottom: 12px" />
      <el-alert
        v-else-if="templateEmpty"
        title="当前项目暂无已启用的报告模板，请先到模板中心上传并启用报告模板。"
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      >
        <template #default><el-button type="primary" link @click="goTemplates">去模板中心</el-button></template>
      </el-alert>

      <el-form label-width="96px">
        <el-form-item label="报告名称" required><el-input v-model="form.reportName" placeholder="请输入报告名称" /></el-form-item>
        <el-form-item label="报告类型" required>
          <el-select v-model="form.reportType" style="width: 100%">
            <el-option label="安全月报" value="SAFETY_MONTHLY" />
            <el-option label="质量周报" value="QUALITY_WEEKLY" />
            <el-option label="综合报告" value="GENERAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="报告模板" required>
          <el-select v-model="form.templateId" style="width: 100%" placeholder="请选择报告模板" clearable>
            <el-option v-for="item in templates" :key="item.templateId" :label="item.templateName" :value="item.templateId" />
          </el-select>
        </el-form-item>
        <el-form-item label="知识库">
          <el-select v-model="form.knowledgeBaseIds" multiple collapse-tags style="width: 100%" placeholder="可多选项目知识库" clearable>
            <el-option v-for="item in knowledgeBases" :key="item.knowledgeBaseId" :label="item.name" :value="item.knowledgeBaseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源">
          <el-select v-model="form.dataSourceIds" multiple collapse-tags style="width: 100%" placeholder="可多选数据库数据源" clearable>
            <el-option v-for="item in dataSources" :key="item.dataSourceId" :label="item.name" :value="item.dataSourceId" />
          </el-select>
          <div class="form-tip">知识库和数据源至少选择一项。系统按模板变量自动判断使用知识检索、数据库查询或混合生成。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!canCreate" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>.form-tip { margin-top: 6px; color: var(--sw-muted); font-size: 12px; line-height: 1.6; }</style>
