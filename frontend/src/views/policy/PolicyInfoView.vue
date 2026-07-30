<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import AppTable from '../../components/common/AppTable.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import { createPolicyCrawlTask, createPolicySource, deletePolicySource, fetchPolicyArticles, fetchPolicyCrawlTasks, fetchPolicySources, updatePolicySource } from '../../api/policy';
import { useProjectStore } from '../../stores/project';
import type { ID, PolicyArticle, PolicyCrawlTask, PolicySource } from '../../api/types';

const projectStore = useProjectStore();
const sourceLoading = ref(false);
const taskLoading = ref(false);
const articleLoading = ref(false);
const saving = ref(false);
const crawlingId = ref<ID | 'ALL' | ''>('');
const sourceError = ref('');
const taskError = ref('');
const articleError = ref('');
const sourceDialogVisible = ref(false);
const sources = ref<PolicySource[]>([]);
const tasks = ref<PolicyCrawlTask[]>([]);
const articles = ref<PolicyArticle[]>([]);
const articlePager = reactive({ pageNo: 1, pageSize: 10, total: 0, keyword: '', sourceId: '' as ID | '', indexStatus: '' });
const form = reactive({ sourceId: '' as ID | '', name: '', url: '', crawlFrequency: 'DAILY', description: '' });
const projectId = computed(() => projectStore.currentProject?.projectId || '');
const activeCrawlSourceIds = computed(() => new Set(tasks.value.filter((task) => isActiveTaskStatus(task.status)).map((task) => task.sourceId || 'ALL')));
let crawlPollTimer: ReturnType<typeof window.setInterval> | null = null;

function resetForm() {
  Object.assign(form, { sourceId: '', name: '', url: '', crawlFrequency: 'DAILY', description: '' });
}

function openCreate() {
  resetForm();
  sourceDialogVisible.value = true;
}

function openEdit(row: PolicySource) {
  Object.assign(form, { sourceId: row.sourceId, name: row.name, url: row.url, crawlFrequency: row.crawlFrequency, description: row.description || '' });
  sourceDialogVisible.value = true;
}

function validateForm() {
  if (!projectId.value) return '请先选择项目';
  if (!form.name.trim()) return '请输入政策源名称';
  if (!form.url.trim()) return '请输入政策源 URL';
  if (!/^https?:\/\//i.test(form.url.trim())) return '政策源 URL 必须以 http:// 或 https:// 开头';
  return '';
}

function isActiveTaskStatus(status: unknown) {
  return ['QUEUED', 'RUNNING', 'RETRYING'].includes(String(status));
}

function hasActiveCrawlTasks() {
  return tasks.value.some((task) => isActiveTaskStatus(task.status));
}

async function ensureProjectSelected() {
  if (!projectStore.currentProject) await projectStore.fetchProjects();
  if (!projectId.value) throw new Error('请先选择项目');
}

function stopCrawlPolling() {
  if (!crawlPollTimer) return;
  window.clearInterval(crawlPollTimer);
  crawlPollTimer = null;
}

function syncCrawlPolling() {
  if (!hasActiveCrawlTasks()) {
    stopCrawlPolling();
    return;
  }
  if (crawlPollTimer) return;
  crawlPollTimer = window.setInterval(() => {
    void pollCrawlProgress();
  }, 3000);
}

async function pollCrawlProgress() {
  const hadActiveTasks = hasActiveCrawlTasks();
  await loadTasks({ silent: true, managePolling: false });
  if (hadActiveTasks && !hasActiveCrawlTasks()) {
    await Promise.all([loadSources({ silent: true }), loadArticles({ silent: true })]);
  } else if (hasActiveCrawlTasks()) {
    await loadArticles({ silent: true });
  }
  syncCrawlPolling();
}

async function loadSources(options: { silent?: boolean } = {}) {
  if (!options.silent) sourceLoading.value = true;
  sourceError.value = '';
  try {
    await ensureProjectSelected();
    const page = await fetchPolicySources({ projectId: projectId.value, pageNo: 1, pageSize: 100 });
    sources.value = page.records;
  } catch (err) {
    sources.value = [];
    sourceError.value = err instanceof Error ? err.message : '政策源加载失败';
  } finally {
    if (!options.silent) sourceLoading.value = false;
  }
}

async function loadTasks(options: { silent?: boolean; managePolling?: boolean } = {}) {
  if (!options.silent) taskLoading.value = true;
  taskError.value = '';
  try {
    await ensureProjectSelected();
    const page = await fetchPolicyCrawlTasks({ projectId: projectId.value, pageNo: 1, pageSize: 5 });
    tasks.value = page.records;
  } catch (err) {
    tasks.value = [];
    taskError.value = err instanceof Error ? err.message : '爬取任务加载失败';
  } finally {
    if (!options.silent) taskLoading.value = false;
    if (options.managePolling !== false) syncCrawlPolling();
  }
}

async function loadArticles(options: { silent?: boolean } = {}) {
  if (!options.silent) articleLoading.value = true;
  articleError.value = '';
  try {
    await ensureProjectSelected();
    const page = await fetchPolicyArticles({
      projectId: projectId.value,
      pageNo: articlePager.pageNo,
      pageSize: articlePager.pageSize,
      keyword: articlePager.keyword,
      sourceId: articlePager.sourceId || undefined,
      indexStatus: articlePager.indexStatus || undefined
    });
    articles.value = page.records;
    articlePager.total = page.total;
  } catch (err) {
    articles.value = [];
    articlePager.total = 0;
    articleError.value = err instanceof Error ? err.message : '政策资讯加载失败';
  } finally {
    if (!options.silent) articleLoading.value = false;
  }
}

async function refreshAll() {
  await ensureProjectSelected();
  await Promise.all([loadSources(), loadTasks(), loadArticles()]);
}

async function saveSource() {
  const message = validateForm();
  if (message) return ElMessage.warning(message);
  saving.value = true;
  try {
    if (form.sourceId) await updatePolicySource(form.sourceId, { name: form.name.trim(), url: form.url.trim(), crawlFrequency: form.crawlFrequency, description: form.description.trim() || undefined });
    else await createPolicySource({ projectId: projectId.value, name: form.name.trim(), url: form.url.trim(), crawlFrequency: form.crawlFrequency, description: form.description.trim() || undefined });
    ElMessage.success(form.sourceId ? '政策源已更新' : '政策源已创建');
    sourceDialogVisible.value = false;
    await loadSources();
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '政策源保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeSource(row: PolicySource) {
  try {
    await ElMessageBox.confirm(`确认删除政策源「${row.name}」？`, '删除政策源', { type: 'warning' });
    await deletePolicySource(row.sourceId);
    ElMessage.success('政策源已删除');
    await refreshAll();
  } catch (err) {
    if (err === 'cancel' || err === 'close') return;
    ElMessage.error(err instanceof Error ? err.message : '政策源删除失败');
  }
}

async function crawl(sourceId?: ID) {
  if (!projectId.value) return ElMessage.warning('请先选择项目');
  if (activeCrawlSourceIds.value.has(sourceId || 'ALL')) return ElMessage.warning('该政策源已有进行中的爬取任务');
  crawlingId.value = sourceId || 'ALL';
  try {
    await createPolicyCrawlTask({ projectId: projectId.value, sourceId });
    ElMessage.success('政策资讯爬取任务已提交，页面将自动刷新进度');
    await refreshAll();
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '政策资讯爬取任务提交失败');
  } finally {
    crawlingId.value = '';
  }
}

function isCrawlingSource(sourceId: ID) {
  return crawlingId.value === sourceId || activeCrawlSourceIds.value.has(sourceId);
}

function searchArticles() {
  articlePager.pageNo = 1;
  void loadArticles();
}

onMounted(() => {
  void refreshAll();
});

onBeforeUnmount(stopCrawlPolling);

watch(projectId, () => {
  stopCrawlPolling();
  tasks.value = [];
  articles.value = [];
  articlePager.total = 0;
  if (projectId.value) void refreshAll();
});
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">政策资讯</h2>
        <p class="page-desc">配置互联网政策资讯来源，提交真实爬取任务并自动刷新知识更新状态，支撑知识问答的政策来源。</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" :loading="crawlingId === 'ALL'" :disabled="activeCrawlSourceIds.has('ALL')" @click="crawl()">爬取全部</el-button>
        <el-button type="primary" @click="openCreate">新增政策源</el-button>
      </div>
    </div>

    <div class="two-col policy-grid">
      <el-card class="work-card">
        <template #header><strong>政策源配置</strong></template>
        <AppTable :loading="sourceLoading" :error="sourceError" :data="sources" :columns="[{ prop: 'name', label: '来源名称', slot: 'sourceName' }, { prop: 'crawlFrequency', label: '频率', width: 90 }, { prop: 'status', label: '状态', slot: 'status', width: 100 }]">
          <template #empty><EmptyState description="暂无政策源，可先新增一个互联网政策栏目地址。" /></template>
          <template #sourceName="{ row }"><div><strong>{{ row.name }}</strong><p class="muted url-text">{{ row.url }}</p></div></template>
          <template #status="{ row }"><StatusTag :status="row.status" /></template>
          <el-table-column label="操作" width="210">
            <template #default="{ row }">
              <el-button link type="primary" :loading="crawlingId === row.sourceId" :disabled="isCrawlingSource(row.sourceId)" @click="crawl(row.sourceId)">爬取</el-button>
              <el-button link @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="removeSource(row)">删除</el-button>
            </template>
          </el-table-column>
        </AppTable>
      </el-card>

      <el-card class="work-card">
        <template #header><strong>最近爬取任务</strong></template>
        <el-alert v-if="taskError" :title="taskError" type="error" show-icon :closable="false" style="margin-bottom:12px" />
        <EmptyState v-if="!taskLoading && !tasks.length" description="暂无爬取任务" />
        <div v-for="task in tasks" :key="task.taskId" class="task-card">
          <div class="task-head"><strong>{{ task.sourceName || '全部政策源' }}</strong><StatusTag :status="task.status" /></div>
          <el-progress :percentage="task.progress || 0" :status="task.status === 'FAILED' ? 'exception' : 'success'" />
          <p class="muted">抓取 {{ task.fetchedCount }} 条 / 入库 {{ task.indexedCount }} 条，{{ task.message || '等待执行' }}</p>
        </div>
      </el-card>
    </div>

    <el-card class="work-card">
      <template #header>
        <div class="table-head">
          <strong>政策资讯列表</strong>
          <div class="filters">
            <el-input v-model="articlePager.keyword" clearable placeholder="搜索标题/摘要" style="width:220px" @keyup.enter="searchArticles" @clear="searchArticles" />
            <el-select v-model="articlePager.sourceId" clearable placeholder="政策源" style="width:180px" @change="searchArticles">
              <el-option v-for="source in sources" :key="source.sourceId" :label="source.name" :value="source.sourceId" />
            </el-select>
            <el-select v-model="articlePager.indexStatus" clearable placeholder="入库状态" style="width:140px" @change="searchArticles">
              <el-option label="已入库" value="SUCCESS" />
              <el-option label="入库中" value="INDEXING" />
              <el-option label="失败" value="FAILED" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="searchArticles">查询</el-button>
          </div>
        </div>
      </template>
      <AppTable :loading="articleLoading" :error="articleError" :data="articles" :total="articlePager.total" :page-no="articlePager.pageNo" :page-size="articlePager.pageSize" :columns="[{ prop: 'title', label: '标题', slot: 'title' }, { prop: 'category', label: '分类', width: 120 }, { prop: 'publishDate', label: '发布日期', width: 130 }, { prop: 'indexStatus', label: '入库状态', slot: 'indexStatus', width: 120 }]" @page-change="(p, s) => { articlePager.pageNo = p; articlePager.pageSize = s; loadArticles(); }">
        <template #empty><EmptyState description="暂无政策资讯，请先触发政策源爬取。" /></template>
        <template #title="{ row }"><div><strong>{{ row.title }}</strong><p class="muted">{{ row.summary }}</p><p class="muted url-text">{{ row.url }}</p></div></template>
        <template #indexStatus="{ row }"><StatusTag :status="row.indexStatus" /></template>
      </AppTable>
    </el-card>

    <el-dialog v-model="sourceDialogVisible" title="政策源配置" width="640px">
      <el-form label-width="96px">
        <el-form-item label="来源名称" required><el-input v-model="form.name" placeholder="例如：住建部政策公开栏目" /></el-form-item>
        <el-form-item label="栏目地址" required><el-input v-model="form.url" placeholder="https://example.gov.cn/policy" /></el-form-item>
        <el-form-item label="爬取频率" required>
          <el-select v-model="form.crawlFrequency" style="width:100%">
            <el-option label="手动" value="MANUAL" />
            <el-option label="每日" value="DAILY" />
            <el-option label="每周" value="WEEKLY" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" placeholder="记录采集范围、栏目说明或更新要求" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="sourceDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveSource">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions, .filters, .table-head, .task-head { display: flex; align-items: center; gap: 10px; }
.header-actions { justify-content: flex-end; }
.table-head { justify-content: space-between; flex-wrap: wrap; }
.filters { flex-wrap: wrap; justify-content: flex-end; }
.policy-grid { align-items: start; }
.task-card { padding: 12px 0; border-bottom: 1px solid var(--sw-border); }
.task-card:last-child { border-bottom: 0; }
.muted { color: var(--sw-muted); font-size: 13px; line-height: 1.6; }
.url-text { word-break: break-all; margin: 4px 0 0; }
@media (max-width: 960px) { .table-head, .filters, .header-actions { align-items: stretch; flex-direction: column; } }
</style>
