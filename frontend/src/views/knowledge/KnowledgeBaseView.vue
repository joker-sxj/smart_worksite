<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppUpload from '../../components/common/AppUpload.vue';
import AppTable from '../../components/common/AppTable.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import { createKnowledgeBase, deleteKnowledgeBase, deleteKnowledgeDocument, disableKnowledgeBase, enableKnowledgeBase, fetchKnowledgeBaseDetail, fetchKnowledgeBases, fetchKnowledgeDocumentDetail, fetchKnowledgeDocuments, triggerDocumentIndex, updateKnowledgeBase, uploadKnowledgeDocument } from '../../api/knowledge';
import { createFileParse, fetchLatestFileParseRecord, fetchLatestSuccessfulFileParseRecord, type FileParseRecord } from '../../api/file';
import { useProjectStore } from '../../stores/project';
import { useUserStore } from '../../stores/user';
import type { ID, KnowledgeBase, KnowledgeDocument } from '../../api/types';
import { createFileParsePolling } from '../file/fileParsePolling';
import { fileParseStatusText } from '../file/fileParseStatus';
import { fileExtension, isParseableFileName, parseTargetFormatForFileName } from '../file/supportedFileParse';
import { documentParseActionText, documentParseRecord, documentProcessingMessage, isDocumentParseReady, isKnowledgeDocumentIndexReady, isParseableKnowledgeDocument, knowledgeDocumentParseTargetFormat, setDocumentParseRecord, type DocumentParseRecords } from './knowledgeDocumentParseState';

const projectStore = useProjectStore();
const userStore = useUserStore();
const loading = ref(false);
const docsLoading = ref(false);
const creating = ref(false);
const uploading = ref(false);
const error = ref('');
const docsError = ref('');
const bases = ref<KnowledgeBase[]>([]);
const docs = ref<KnowledgeDocument[]>([]);
const documentParseRecords = ref<DocumentParseRecords>({});
const activeBaseId = ref<ID>('');
const dialogVisible = ref(false);
const detailDrawerVisible = ref(false);
const detailLoading = ref(false);
const selectedDocument = ref<KnowledgeDocument | null>(null);
const selectedFiles = ref<File[]>([]);
const indexingId = ref<ID>('');
const parsingId = ref<ID>('');
const form = reactive({ knowledgeBaseId: '', name: '', domain: '', description: '' });
const activeBase = computed(() => bases.value.find((item) => String(item.knowledgeBaseId) === String(activeBaseId.value)) || null);
const canManageKnowledge = computed(() => userStore.hasPermission('knowledge:manage'));
const knowledgeManageTip = '当前账号没有知识库管理权限';
const uploadableExts = new Set(['png', 'jpg', 'jpeg', 'webp', 'pdf', 'doc', 'docx', 'ppt', 'pptx', 'xls', 'xlsx', 'csv']);
let docsRefreshTimer: number | undefined;

function isPolicyBase(base?: KnowledgeBase | null) {
  return String(base?.knowledgeBaseType || 'PROJECT').toUpperCase() === 'POLICY';
}

function normalizeStatus(status?: string) {
  return (status || '').toUpperCase();
}

function canSubmitIndex(row: KnowledgeDocument) {
  return canManageKnowledge.value && isKnowledgeDocumentIndexReady(row.indexStatus, latestParse(row));
}



function isParseableDocument(row: KnowledgeDocument) {
  return isParseableKnowledgeDocument(row);
}

function canParseDocument(row: KnowledgeDocument) {
  return canManageKnowledge.value && isParseableDocument(row);
}

function latestParse(row: KnowledgeDocument) {
  return documentParseRecord(documentParseRecords.value, row.documentId);
}

function isDocumentParsing(row: KnowledgeDocument) {
  return ['PENDING', 'PARSING', 'RUNNING'].includes(normalizeStatus(latestParse(row)?.status));
}

function canStartParse(row: KnowledgeDocument) {
  return canParseDocument(row) && !isDocumentParsing(row);
}

function parseActionText(row: KnowledgeDocument) {
  return documentParseActionText(latestParse(row));
}

function parseStatusText(row: KnowledgeDocument) {
  const record = latestParse(row);
  return record ? fileParseStatusText(record.status) : '未解析';
}

function parseTargetFormat(row: KnowledgeDocument) {
  return knowledgeDocumentParseTargetFormat(row);
}

function processingMessage(row: KnowledgeDocument) {
  return documentProcessingMessage(row.errorMessage, latestParse(row));
}

function parseDisabledReason(row: KnowledgeDocument) {
  if (!canManageKnowledge.value) return knowledgeManageTip;
  if (isDocumentParsing(row)) return '文件正在解析，请等待当前任务完成';
  if (!row.fileId) return '文档缺少 fileId，无法创建文件解析任务';
  const fileType = row.fileExt || row.contentType || row.title?.split('.').pop()?.toLowerCase() || 'unknown';
  return `当前文件格式 ${fileType} 暂不支持解析，无法作为知识库入库来源`;
}

async function refreshDocumentParseRecords(baseId: ID) {
  const currentDocs = docs.value.filter((row) => row.fileId && isParseableDocument(row));
  const results = await Promise.all(currentDocs.map(async (row) => {
    try {
      const record = await fetchLatestFileParseRecord(row.fileId!, row.projectId);
      return [String(row.documentId), record] as const;
    } catch {
      return null;
    }
  }));
  if (String(activeBaseId.value) !== String(baseId)) return false;
  documentParseRecords.value = Object.fromEntries(results.filter((item): item is readonly [string, FileParseRecord] => Boolean(item)));
  return Object.values(documentParseRecords.value).some((record) => ['PENDING', 'PARSING', 'RUNNING'].includes(normalizeStatus(record.status)));
}

const parsePolling = createFileParsePolling(async () => {
  if (!activeBaseId.value) return false;
  return refreshDocumentParseRecords(activeBaseId.value);
});

function hasIndexingDocs() {
  return docs.value.some((item) => normalizeStatus(item.indexStatus) === 'INDEXING');
}

function stopDocsAutoRefresh() {
  if (docsRefreshTimer) {
    window.clearTimeout(docsRefreshTimer);
    docsRefreshTimer = undefined;
  }
}

function scheduleDocsAutoRefresh() {
  stopDocsAutoRefresh();
  if (!activeBaseId.value || !hasIndexingDocs()) return;
  docsRefreshTimer = window.setTimeout(async () => {
    await loadDocs(activeBaseId.value);
  }, 3000);
}

function indexActionText(row: KnowledgeDocument) {
  const status = normalizeStatus(row.indexStatus);
  if (status === 'INDEXING') return '入库中';
  if (status === 'SUCCESS') return '已入库';
  if (status === 'FAILED') return '重新入库';
  return '入库处理';
}

async function loadBases(selectId?: ID) {
  loading.value = true;
  error.value = '';
  try {
    if (!projectStore.currentProject) await projectStore.fetchProjects();
    const projectId = projectStore.currentProject?.projectId;
    if (!projectId) { bases.value = []; docs.value = []; activeBaseId.value = ''; return; }
    bases.value = await fetchKnowledgeBases(projectId);
    const nextId = selectId || activeBaseId.value;
    const matched = bases.value.find((item) => String(item.knowledgeBaseId) === String(nextId));
    activeBaseId.value = matched ? matched.knowledgeBaseId : (bases.value[0]?.knowledgeBaseId || '');
    if (activeBaseId.value) await loadDocs(activeBaseId.value); else docs.value = [];
  } catch (err) {
    error.value = err instanceof Error ? err.message : '知识库数据加载失败，请检查后端知识库接口。';
  } finally { loading.value = false; }
}

async function loadDocs(baseId: ID) {
  parsePolling.stop();
  docsLoading.value = true;
  docsError.value = '';
  try {
    docs.value = (await fetchKnowledgeDocuments(baseId)).records;
    await parsePolling.start();
  }
  catch (err) { docsError.value = err instanceof Error ? err.message : '文档列表加载失败，请检查后端知识库文档接口。'; docs.value = []; documentParseRecords.value = {}; }
  finally { docsLoading.value = false; scheduleDocsAutoRefresh(); }
}

async function submitCreate() {
  if (!canManageKnowledge.value) return ElMessage.warning(knowledgeManageTip);
  if (!form.name.trim()) return ElMessage.warning('请填写知识库名称');
  const projectId = projectStore.currentProject?.projectId;
  if (!projectId) return ElMessage.warning('请先选择项目');
  creating.value = true;
  error.value = '';
  try {
    const editing = Boolean(form.knowledgeBaseId);
    const saved = form.knowledgeBaseId
      ? await updateKnowledgeBase(form.knowledgeBaseId, { name: form.name.trim(), domain: form.domain.trim() || undefined, description: form.description.trim() })
      : await createKnowledgeBase(projectId, { name: form.name.trim(), domain: form.domain.trim() || undefined, description: form.description.trim() });
    dialogVisible.value = false; Object.assign(form, { knowledgeBaseId: '', name: '', domain: '', description: '' });
    ElMessage.success(editing ? '知识库已保存' : '知识库创建成功');
    await loadBases(saved.knowledgeBaseId);
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    ElMessage.error(`知识库创建失败，请检查后端知识库接口。${detail}`);
  } finally { creating.value = false; }
}

function openCreateBase() {
  Object.assign(form, { knowledgeBaseId: '', name: '', domain: '', description: '' });
  dialogVisible.value = true;
}

async function openEditBase(base: KnowledgeBase) {
  if (!canManageKnowledge.value) return ElMessage.warning(knowledgeManageTip);
  try {
    const detail = await fetchKnowledgeBaseDetail(base.knowledgeBaseId);
    Object.assign(form, {
      knowledgeBaseId: String(detail.knowledgeBaseId),
      name: detail.name,
      domain: detail.domain || '',
      description: detail.description || ''
    });
    dialogVisible.value = true;
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '知识库详情加载失败');
  }
}

async function setBaseStatus(base: KnowledgeBase, enabled: boolean) {
  if (!canManageKnowledge.value) return ElMessage.warning(knowledgeManageTip);
  try {
    enabled ? await enableKnowledgeBase(base.knowledgeBaseId) : await disableKnowledgeBase(base.knowledgeBaseId);
    ElMessage.success(enabled ? '知识库已启用' : '知识库已停用');
    await loadBases(base.knowledgeBaseId);
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '知识库状态更新失败');
  }
}

async function removeBase(base: KnowledgeBase) {
  if (!canManageKnowledge.value) return ElMessage.warning(knowledgeManageTip);
  try {
    await ElMessageBox.confirm(`确认删除知识库“${base.name}”？`, '删除知识库', { type: 'warning' });
    await deleteKnowledgeBase(base.knowledgeBaseId);
    ElMessage.success('知识库已删除');
    await loadBases();
  } catch (err) {
    if (err === 'cancel' || err === 'close') return;
    ElMessage.error(err instanceof Error ? err.message : '知识库删除失败');
  }
}

async function openDocumentDetail(row: KnowledgeDocument) {
  detailDrawerVisible.value = true;
  detailLoading.value = true;
  selectedDocument.value = null;
  try {
    selectedDocument.value = await fetchKnowledgeDocumentDetail(row.documentId);
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '知识文档详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

async function removeDocument(row: KnowledgeDocument) {
  if (!canManageKnowledge.value) return ElMessage.warning(knowledgeManageTip);
  try {
    await ElMessageBox.confirm(`确认删除文档“${row.title}”？`, '删除文档', { type: 'warning' });
    await deleteKnowledgeDocument(row.documentId);
    ElMessage.success('文档已删除');
    await loadDocs(activeBaseId.value);
  } catch (err) {
    if (err === 'cancel' || err === 'close') return;
    ElMessage.error(err instanceof Error ? err.message : '知识文档删除失败');
  }
}

async function uploadDocs() {
  if (!canManageKnowledge.value) return ElMessage.warning(knowledgeManageTip);
  if (!activeBaseId.value) return ElMessage.warning('请先选择知识库');
  if (isPolicyBase(activeBase.value)) return ElMessage.warning('政策资讯库由系统爬虫维护，不支持手动上传');
  if (!selectedFiles.value.length) return ElMessage.warning('请先选择文件');
  uploading.value = true;
  docsError.value = '';
  try {
    const unsupported = selectedFiles.value.filter((file) => !uploadableExts.has(fileExtension(file.name)));
    if (unsupported.length) {
      ElMessage.error(`以下文件暂不支持知识库解析入库：${unsupported.map((file) => file.name).join('、')}`);
      return;
    }
    for (const file of selectedFiles.value) await uploadKnowledgeDocument(activeBaseId.value, file);
    ElMessage.success('文档上传成功');
    selectedFiles.value = [];
    await loadDocs(activeBaseId.value);
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    ElMessage.error(`知识库文档上传失败，请检查后端知识库文档接口或文件存储配置。${detail}`);
  } finally { uploading.value = false; }
}

async function handleParse(row: KnowledgeDocument) {
  if (!canManageKnowledge.value) return ElMessage.warning(knowledgeManageTip);
  if (!row.fileId) return ElMessage.warning('文档缺少 fileId，无法解析');
  if (!canParseDocument(row)) return ElMessage.warning(parseDisabledReason(row));
  parsingId.value = row.documentId;
  docsError.value = '';
  try {
    const record = await createFileParse(row.fileId, { projectId: row.projectId, targetFormat: parseTargetFormat(row) });
    documentParseRecords.value = setDocumentParseRecord(documentParseRecords.value, row.documentId, record);
    ElMessage.success('文件解析任务已提交，解析成功后再执行入库');
    void parsePolling.start();
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    ElMessage.error(`文件解析任务提交失败。${detail}`);
  } finally {
    parsingId.value = '';
  }
}

async function handleIndex(row: KnowledgeDocument) {
  if (!canManageKnowledge.value) return ElMessage.warning(knowledgeManageTip);
  const documentId = row.documentId;
  if (!documentId) return ElMessage.warning('文档ID缺失，无法触发入库');
  if (!row.fileId) return ElMessage.warning('文档缺少 fileId，无法触发入库');
  if (!canSubmitIndex(row)) return ElMessage.warning(`当前文档状态为 ${row.indexStatus}，不能重复提交入库任务`);
  try {
    const latestParse = await fetchLatestSuccessfulFileParseRecord(row.fileId, row.projectId);
    if (!isDocumentParseReady(latestParse)) {
      ElMessage.warning(`最新成功解析结果状态异常，请重新解析后再入库`);
      return;
    }
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    ElMessage.error(`入库前未找到可用的成功解析结果，请先点击“解析文件”。${detail}`);
    return;
  }
  indexingId.value = documentId;
  docsError.value = '';
  try { await triggerDocumentIndex(documentId); ElMessage.success('入库任务已提交，状态将自动刷新'); await loadDocs(activeBaseId.value); }
  catch (err) { const detail = err instanceof Error && err.message ? ` ${err.message}` : ''; ElMessage.error(`入库任务提交失败，请检查后端知识库入库接口。${detail}`); }
  finally { indexingId.value = ''; }
}

watch(activeBaseId, (id) => { stopDocsAutoRefresh(); parsePolling.stop(); documentParseRecords.value = {}; if (id) loadDocs(id); });
onMounted(loadBases);
onUnmounted(() => { stopDocsAutoRefresh(); parsePolling.stop(); });
</script>

<template>
  <div class="page" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div class="page-header"><div><h2 class="page-title">知识库管理</h2><p class="page-desc">项目级知识库、文档解析状态和入库进度。</p></div><el-button v-if="canManageKnowledge" type="primary" @click="openCreateBase">新建知识库</el-button></div>
    <EmptyState v-if="!loading && !bases.length" description="暂无知识库，请联系知识库管理员创建。" :action-text="canManageKnowledge ? '创建知识库' : undefined" @action="openCreateBase" />
    <template v-else>
      <el-card class="work-card"><div class="base-list"><div v-for="base in bases" :key="base.knowledgeBaseId" class="base-card" :class="{ active: String(activeBaseId) === String(base.knowledgeBaseId) }" @click="activeBaseId = base.knowledgeBaseId"><strong>{{ base.name }} <el-tag v-if="isPolicyBase(base)" size="small" type="warning">系统政策库</el-tag></strong><span>{{ base.description || '暂无描述' }}</span><small>领域：{{ base.domain || '-' }} / <StatusTag :status="base.status" /></small><div v-if="canManageKnowledge && !isPolicyBase(base)" class="base-actions"><el-button link type="primary" @click.stop="openEditBase(base)">编辑</el-button><el-button link :type="['ENABLED','ACTIVE'].includes(String(base.status).toUpperCase()) ? 'warning' : 'success'" @click.stop="setBaseStatus(base, !['ENABLED','ACTIVE'].includes(String(base.status).toUpperCase()))">{{ ['ENABLED','ACTIVE'].includes(String(base.status).toUpperCase()) ? '停用' : '启用' }}</el-button><el-button link type="danger" @click.stop="removeBase(base)">删除</el-button></div></div></div><p v-if="activeBase" class="muted">当前知识库：{{ activeBase.name }} / <StatusTag :status="activeBase.status" /></p></el-card>
      <el-card class="work-card"><h3 class="panel-title">上传文档</h3><el-alert v-if="isPolicyBase(activeBase)" title="政策资讯库由系统爬虫维护，不支持手动上传、编辑、停用或删除。" type="warning" show-icon :closable="false" style="margin-bottom: 12px" /><el-alert v-else title="可上传 Word、PPT、Excel/CSV、PDF 和图片。" type="info" show-icon :closable="false" style="margin-bottom: 12px" /><div class="upload-title required-label">知识库文档</div><AppUpload v-model="selectedFiles" accept=".doc,.docx,.ppt,.pptx,.xls,.xlsx,.csv,.pdf,.jpg,.jpeg,.png,.webp" :max-size-mb="100" tip="支持 Word、PPT、Excel/CSV、PDF 和图片；入库前需完成可解析文件内容抽取" :uploading="uploading"  /><el-button type="primary" style="margin-top: 12px" :loading="uploading" :disabled="!activeBaseId || isPolicyBase(activeBase)" @click="uploadDocs">上传到当前知识库</el-button></el-card>
      <el-card class="work-card"><h3 class="panel-title">文档处理状态</h3><el-alert v-if="docsError" :title="docsError" type="error" show-icon :closable="false" style="margin-bottom: 12px" /><AppTable :loading="docsLoading" :data="docs" :columns="[{ prop: 'title', label: '文档名称' }, { prop: 'sourceType', label: '来源类型', width: 120 }, { prop: 'parseStatus', label: '解析状态', slot: 'parseStatus', width: 110 }, { prop: 'indexStatus', label: '入库状态', slot: 'index', width: 110 }, { prop: 'errorMessage', label: '说明' }, { prop: 'createdAt', label: '创建时间', width: 180 }]"><template #empty><EmptyState description="暂无知识库文档，可先上传项目资料。" /></template><template #parseStatus="{ row }"><StatusTag :status="latestParse(row)?.status" :text="parseStatusText(row)" /></template><template #index="{ row }"><StatusTag :status="row.indexStatus" /></template><el-table-column label="操作" width="350"><template #default="{ row }"><el-button link type="primary" @click="openDocumentDetail(row)">详情</el-button><el-tooltip :disabled="canStartParse(row)" :content="parseDisabledReason(row)"><span><el-button link type="primary" :loading="String(parsingId) === String(row.documentId)" :disabled="!canStartParse(row)" @click="handleParse(row)">{{ parseActionText(row) }}</el-button></span></el-tooltip><el-button link type="primary" :loading="String(indexingId) === String(row.documentId)" :disabled="!canSubmitIndex(row)" @click="handleIndex(row)">{{ indexActionText(row) }}</el-button><el-button v-if="canManageKnowledge" link type="danger" @click="removeDocument(row)">删除</el-button></template></el-table-column></AppTable><template v-for="row in docs" :key="`processing-${row.documentId}`"><p v-if="processingMessage(row)" class="muted">{{ row.title }}：{{ processingMessage(row) }}</p></template></el-card>
    </template>
    <el-dialog v-model="dialogVisible" :title="form.knowledgeBaseId ? '编辑知识库' : '新建知识库'" width="520px"><el-form label-width="96px"><el-form-item label="知识库名称" required><el-input v-model="form.name" placeholder="请输入知识库名称" /></el-form-item><el-form-item label="领域"><el-input v-model="form.domain" placeholder="如 SAFETY、QUALITY" /></el-form-item><el-form-item label="描述"><el-input v-model="form.description" type="textarea" placeholder="请输入知识库描述" /></el-form-item></el-form><template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="creating" @click="submitCreate">保存</el-button></template></el-dialog>
    <el-drawer v-model="detailDrawerVisible" title="知识文档详情" size="520px">
      <div v-loading="detailLoading">
        <EmptyState v-if="!selectedDocument" description="暂无文档详情。" />
        <el-descriptions v-else :column="1" border>
          <el-descriptions-item label="文档ID">{{ selectedDocument.documentId }}</el-descriptions-item>
          <el-descriptions-item label="文件ID">{{ selectedDocument.fileId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ selectedDocument.title }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ selectedDocument.sourceType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="解析状态"><StatusTag :status="latestParse(selectedDocument)?.status" :text="parseStatusText(selectedDocument)" /></el-descriptions-item>
          <el-descriptions-item label="入库状态"><StatusTag :status="selectedDocument.indexStatus" /></el-descriptions-item>
          <el-descriptions-item label="任务ID">{{ selectedDocument.taskId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="错误信息">{{ selectedDocument.errorMessage || '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ selectedDocument.updatedAt }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.base-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.base-card { text-align: left; border: 1px solid var(--sw-border); border-radius: 12px; background: #fff; padding: 14px; cursor: pointer; display: grid; gap: 8px; color: inherit; }
.base-card.active { border-color: var(--sw-primary); box-shadow: 0 0 0 3px rgba(30, 94, 255, 0.12); }
.base-card span, .base-card small, .muted { color: var(--sw-muted); }
.base-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.panel-title { margin: 0 0 12px; font-size: 16px; }
.upload-title { margin: 0 0 10px; font-weight: 700; }
</style>
