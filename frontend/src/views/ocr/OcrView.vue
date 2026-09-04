<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppUpload from '../../components/common/AppUpload.vue';
import AppTable from '../../components/common/AppTable.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import { fetchFileContent, fetchFileDetail } from '../../api/file';
import { deleteOcrRecord, fetchOcrDownloadResult, fetchOcrRecord, fetchOcrRecords, fetchOcrTypes, retryOcrRecord, submitOcrRecord, updateOcrFields } from '../../api/ocr';
import { useProjectStore } from '../../stores/project';
import { useUserStore } from '../../stores/user';
import type { ID, OcrRecord, OcrTypeTemplate } from '../../api/types';
import { createOcrPreviewController } from './ocrPreview';
import { normalizeCustomFields, serializeCustomFields, type OcrCustomField } from './ocrCustomFields';

const projectStore = useProjectStore();
const userStore = useUserStore();
const loading = ref(false);
const recordsLoading = ref(false);
const submitting = ref(false);
const error = ref('');
const notice = ref('');
const record = ref<OcrRecord | null>(null);
const records = ref<OcrRecord[]>([]);
const total = ref(0);
const retryingId = ref<ID | ''>('');
const deletingId = ref<ID | ''>('');
const downloadingId = ref<ID | ''>('');
const OCR_TYPE_STORAGE_KEY = 'smart-worksite:ocr:type';
const DEFAULT_OCR_TYPE = 'CUSTOM';
function readStoredOcrType() {
  try {
    return localStorage.getItem(OCR_TYPE_STORAGE_KEY) || DEFAULT_OCR_TYPE;
  } catch {
    return DEFAULT_OCR_TYPE;
  }
}
function saveStoredOcrType(value: string) {
  if (!value) return;
  try {
    localStorage.setItem(OCR_TYPE_STORAGE_KEY, value);
  } catch {
    // localStorage may be unavailable in restricted browser contexts.
  }
}
const ocrType = ref(readStoredOcrType());
const ocrTypes = ref<OcrTypeTemplate[]>([]);
const customFieldRows = ref<OcrCustomField[]>(normalizeCustomFields([
  { fieldKey: 'partyA', fieldName: '甲方', description: '合同中的甲方名称', required: true, valueType: 'TEXT' },
  { fieldKey: 'partyB', fieldName: '乙方', description: '合同中的乙方名称', required: true, valueType: 'TEXT' },
  { fieldKey: 'contractAmount', fieldName: '合同金额', description: '合同总金额', required: false, valueType: 'AMOUNT' }
]));
const file = ref<File | null>(null);
const invoiceType = ref('VAT_SPECIAL');
const previewUrl = ref('');
const recordPreviewUrl = ref('');
const recordPreviewName = ref('');
const recordPreviewIsImage = ref(false);
const recordPreviewError = ref('');
const query = reactive({ pageNo: 1, pageSize: 10, status: '', ocrType: '' });
const currentProjectId = computed(() => projectStore.currentProject?.projectId);
const canManageOcr = computed(() => userStore.hasPermission('ocr:manage'));
const canSubmit = computed(() => Boolean(canManageOcr.value && currentProjectId.value && file.value && !submitting.value));
const isPreviewImage = computed(() => Boolean(recordPreviewUrl.value ? recordPreviewIsImage.value : file.value?.type.startsWith('image/') && previewUrl.value));
const activePreviewUrl = computed(() => recordPreviewUrl.value || previewUrl.value);
const activePreviewName = computed(() => recordPreviewName.value || file.value?.name || '');
const fallbackOcrTypes = [
  { label: '身份证识别', value: 'ID_CARD' },
  { label: '车牌识别', value: 'LICENSE_PLATE' },
  { label: '发票识别', value: 'INVOICE' },
  { label: '自定义字段识别', value: 'CUSTOM' }
];
const ocrTypeOptions = computed(() => {
  const source = ocrTypes.value.length
    ? ocrTypes.value.map((item) => ({ label: item.name, value: item.ocrType }))
    : fallbackOcrTypes;
  return source;
});
const invoiceTypes = [
  { label: '增值税专用发票', value: 'VAT_SPECIAL' },
  { label: '增值税普通发票', value: 'VAT_NORMAL' }
];
const retryableStatuses = new Set(['FAILED']);
const downloadableStatuses = new Set(['SUCCESS', 'PARTIAL_SUCCESS']);
const terminalStatuses = new Set(['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELED']);
const ocrStatuses = ['PENDING', 'PROCESSING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELED'];
let pollTimer: number | undefined;
let pollCount = 0;

function normalizeStatus(status?: string) {
  return (status || '').toUpperCase();
}

function canRetryRecord(item: OcrRecord) {
  return canManageOcr.value && retryableStatuses.has(normalizeStatus(item.status));
}

function canDownloadRecord(item: OcrRecord) {
  return downloadableStatuses.has(normalizeStatus(item.status));
}

function canSaveFields() {
  return Boolean(canManageOcr.value && record.value && ['SUCCESS', 'PARTIAL_SUCCESS'].includes(normalizeStatus(record.value.status)));
}

function ocrTypeLabel(type?: string) {
  return ocrTypeOptions.value.find((item) => item.value === type)?.label || type || '-';
}

function clearPreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = '';
}

const recordPreviewController = createOcrPreviewController({
  load: async (fileId) => {
    const [detail, blob] = await Promise.all([
      fetchFileDetail(fileId),
      fetchFileContent(fileId)
    ]);
    return {
      blob,
      name: detail.fileName || `OCR 文件 #${fileId}`,
      isImage: Boolean(
        detail.contentType?.startsWith('image/')
        || blob.type.startsWith('image/')
        || ['jpg', 'jpeg', 'png', 'webp'].includes(String(detail.fileExt || '').toLowerCase())
      )
    };
  },
  onChange: (preview) => {
    recordPreviewUrl.value = preview.url;
    recordPreviewName.value = preview.name;
    recordPreviewIsImage.value = preview.isImage;
    recordPreviewError.value = preview.error;
  }
});

function clearRecordPreview() {
  recordPreviewController.clear();
}

function handleFileChange(files: File[]) {
  clearPreview();
  clearRecordPreview();
  file.value = files[0] || null;
  if (file.value?.type.startsWith('image/')) previewUrl.value = URL.createObjectURL(file.value);
}

function stopPolling() {
  if (pollTimer) window.clearInterval(pollTimer);
  pollTimer = undefined;
  pollCount = 0;
}

async function loadRecords() {
  if (!currentProjectId.value) {
    records.value = [];
    total.value = 0;
    return;
  }
  recordsLoading.value = true;
  error.value = '';
  try {
    const page = await fetchOcrRecords({
      projectId: currentProjectId.value,
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      status: query.status || undefined,
      ocrType: query.ocrType || undefined
    });
    records.value = page.records;
    total.value = page.total;
  } catch (err) {
    records.value = [];
    total.value = 0;
    error.value = err instanceof Error ? err.message : 'OCR 记录加载失败，请检查后端 OCR 接口。';
  } finally {
    recordsLoading.value = false;
  }
}

async function loadRecord(recordId: ID) {
  try {
    record.value = await fetchOcrRecord(recordId);
    await loadRecordPreview(record.value);
    notice.value = '';
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    notice.value = `OCR 结果刷新暂时失败，已保留当前记录和原图。${detail}`;
  }
}

async function loadRecordPreview(item: OcrRecord | null) {
  if (!item?.fileId) {
    clearRecordPreview();
    return;
  }
  await recordPreviewController.show(item.fileId);
}

async function loadOcrTypes() {
  try {
    ocrTypes.value = await fetchOcrTypes();
  } catch {
    ocrTypes.value = [];
  }
  const options = ocrTypeOptions.value;
  if (!options.some((item) => item.value === ocrType.value)) {
    ocrType.value = options.some((item) => item.value === DEFAULT_OCR_TYPE) ? DEFAULT_OCR_TYPE : (options[0]?.value || DEFAULT_OCR_TYPE);
  }
}

function pollRecord(recordId: ID) {
  stopPolling();
  pollTimer = window.setInterval(async () => {
    pollCount += 1;
    await loadRecord(recordId);
    await loadRecords();
    const status = normalizeStatus(record.value?.status);
    if (terminalStatuses.has(status) || pollCount >= 60) stopPolling();
  }, 2000);
}

function validateCustomFields() {
  try {
    customFieldRows.value = normalizeCustomFields(customFieldRows.value);
    return true;
  } catch (err) {
    error.value = err instanceof Error ? `自定义字段 JSON 无效：${err.message}` : '自定义字段 JSON 无效';
    return false;
  }
}

async function startOcr() {
  if (!canManageOcr.value) return ElMessage.warning('当前账号没有 OCR 操作权限');
  if (!file.value) return ElMessage.warning('请先选择识别文件');
  if (!currentProjectId.value) return ElMessage.warning('请先选择项目');
  if (ocrType.value === 'INVOICE' && !invoiceType.value) return ElMessage.warning('请选择发票类型');
  if (ocrType.value === 'CUSTOM' && !validateCustomFields()) return;
  submitting.value = true;
  error.value = '';
  notice.value = '';
  try {
    const result = await submitOcrRecord({
      projectId: currentProjectId.value,
      ocrType: ocrType.value,
      file: file.value,
      invoiceType: ocrType.value === 'INVOICE' ? invoiceType.value : undefined,
      customFields: ocrType.value === 'CUSTOM' ? serializeCustomFields(customFieldRows.value) : undefined
    });
    ElMessage.success('OCR 识别任务已提交');
    await loadRecord(result.recordId);
    await loadRecords();
    if (!terminalStatuses.has(normalizeStatus(record.value?.status || result.status))) pollRecord(result.recordId);
  } catch (err) {
    error.value = err instanceof Error ? err.message : '开始识别失败，请确认后端 OCR 接口是否可用。';
  } finally {
    submitting.value = false;
  }
}

async function selectRecord(row: OcrRecord) {
  await loadRecord(row.recordId);
  if (terminalStatuses.has(normalizeStatus(row.status))) stopPolling();
  else pollRecord(row.recordId);
}

async function retryRecord(row: OcrRecord) {
  if (!canRetryRecord(row)) return ElMessage.warning(`当前 OCR 状态为 ${row.status}，不能重试`);
  retryingId.value = row.recordId;
  error.value = '';
  try {
    const result = await retryOcrRecord(row.recordId);
    ElMessage.success('OCR 重试任务已提交');
    await loadRecord(result.recordId);
    await loadRecords();
    pollRecord(result.recordId);
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'OCR 重试失败，请检查后端 OCR 接口。';
  } finally {
    retryingId.value = '';
  }
}

async function saveFields() {
  if (!record.value) return;
  if (!canSaveFields()) return ElMessage.warning(`当前 OCR 状态为 ${record.value.status}，识别成功后才能保存修订`);
  loading.value = true;
  error.value = '';
  try {
    record.value = await updateOcrFields(record.value.recordId, record.value.fields);
    ElMessage.success('修订已保存');
    await loadRecords();
  } catch (err) {
    error.value = err instanceof Error ? err.message : '保存修订失败';
  } finally {
    loading.value = false;
  }
}

async function removeRecord(row: OcrRecord) {
  if (!canManageOcr.value) return ElMessage.warning('当前账号没有 OCR 操作权限');
  try {
    await ElMessageBox.confirm(`确认删除 OCR 记录 #${row.recordId}？`, '删除 OCR 记录', { type: 'warning' });
    deletingId.value = row.recordId;
    await deleteOcrRecord(row.recordId);
    ElMessage.success('OCR 记录已删除');
    if (record.value && String(record.value.recordId) === String(row.recordId)) {
      record.value = null;
      clearRecordPreview();
    }
    await loadRecords();
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') {
      error.value = err instanceof Error ? err.message : 'OCR 记录删除失败，请检查后端 OCR 接口。';
    }
  } finally {
    deletingId.value = '';
  }
}

async function downloadRecord(row: OcrRecord) {
  if (!canDownloadRecord(row)) return ElMessage.warning(`当前 OCR 状态为 ${row.status}，识别成功后才能下载结果`);
  downloadingId.value = row.recordId;
  try {
    const result = await fetchOcrDownloadResult(row.recordId);
    const json = JSON.stringify(result, null, 2);
    const blob = new Blob([json], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    try {
      link.href = url;
      link.download = `ocr-${row.recordId}.json`;
      document.body.appendChild(link);
      link.click();
    } finally {
      if (link.parentNode) document.body.removeChild(link);
      URL.revokeObjectURL(url);
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'OCR 结果下载失败，请检查后端 OCR 接口。';
  } finally {
    downloadingId.value = '';
  }
}

watch(currentProjectId, () => {
  query.pageNo = 1;
  record.value = null;
  clearRecordPreview();
  void loadRecords();
});

watch(ocrType, (value) => saveStoredOcrType(value));

onMounted(async () => {
  if (!projectStore.currentProject) await projectStore.fetchProjects();
  await loadOcrTypes();
  await loadRecords();
});

onUnmounted(() => {
  stopPolling();
  clearPreview();
  recordPreviewController.dispose();
});
</script>

<template>
  <div class="page">
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <el-alert v-if="notice" :title="notice" type="info" show-icon :closable="false" />
    <div class="page-header">
      <div>
        <h2 class="page-title">OCR 识别</h2>
        <p class="page-desc">图片/文档上传、识别状态和结构化字段展示。</p>
      </div>
    </div>

    <el-card class="work-card">
      <template #header>
        <div class="table-head">
          <strong>OCR 记录</strong>
          <div>
            <el-select v-model="query.status" clearable placeholder="状态" style="width: 130px" @change="loadRecords">
              <el-option v-for="item in ocrStatuses" :key="item" :label="item" :value="item" />
            </el-select>
            <el-select v-model="query.ocrType" clearable placeholder="类型" style="width: 160px" @change="loadRecords">
              <el-option v-for="item in ocrTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-button @click="loadRecords">刷新</el-button>
          </div>
        </div>
      </template>
      <AppTable
        :loading="recordsLoading"
        :data="records"
        max-height="276"
        :total="total"
        :page-no="query.pageNo"
        :page-size="query.pageSize"
        :columns="[
          { prop: 'recordId', label: 'ID', width: 90 },
          { prop: 'ocrType', label: '类型', slot: 'ocrType', width: 150 },
          { prop: 'status', label: '状态', slot: 'status', width: 110 },
          { prop: 'progress', label: '进度', width: 90 },
          { prop: 'updatedAt', label: '更新时间', width: 180 }
        ]"
        @page-change="(p, s) => { query.pageNo = p; query.pageSize = s; loadRecords(); }"
      >
        <template #empty><EmptyState description="暂无 OCR 记录" /></template>
        <template #ocrType="{ row }">{{ ocrTypeLabel(row.ocrType) }}</template>
        <template #status="{ row }"><StatusTag :status="row.status" /></template>
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <el-button link type="primary" @click="selectRecord(row)">详情</el-button>
            <el-button link :loading="retryingId === row.recordId" :disabled="!canRetryRecord(row)" @click="retryRecord(row)">重试</el-button>
            <el-button link :loading="downloadingId === row.recordId" :disabled="!canDownloadRecord(row)" @click="downloadRecord(row)">下载</el-button>
            <el-button link type="danger" :loading="deletingId === row.recordId" :disabled="!canManageOcr" @click="removeRecord(row)">删除</el-button>
          </template>
        </el-table-column>
      </AppTable>
    </el-card>

    <div class="two-col">
      <el-card class="work-card">
        <h3 class="panel-title">文件上传与识别设置</h3>
        <el-form label-width="88px">
          <el-form-item label="OCR 类型" required>
            <el-select v-model="ocrType" style="width: 100%">
              <el-option v-for="item in ocrTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="ocrType === 'CUSTOM'" label="自定义字段" required>
            <div class="custom-fields">
              <div v-for="(item, index) in customFieldRows" :key="index" class="custom-field-row">
                <el-input v-model="item.fieldKey" placeholder="字段编码，如 partyA" />
                <el-input v-model="item.fieldName" placeholder="字段名称" maxlength="40" show-word-limit />
                <el-select v-model="item.valueType"><el-option v-for="type in ['TEXT','DATE','NUMBER','AMOUNT','BOOLEAN']" :key="type" :value="type" /></el-select>
                <el-checkbox v-model="item.required">必填</el-checkbox>
                <el-checkbox v-model="item.sensitive">敏感</el-checkbox>
                <el-button text type="danger" :disabled="customFieldRows.length === 1" @click="customFieldRows.splice(index, 1)">删除</el-button>
                <el-input v-model="item.description" class="field-description" placeholder="字段说明（最多 200 字）" maxlength="200" show-word-limit />
              </div>
              <el-button :disabled="customFieldRows.length >= 30" @click="customFieldRows.push(normalizeCustomFields([{ fieldKey: `field${customFieldRows.length + 1}`, fieldName: `字段${customFieldRows.length + 1}`, valueType: 'TEXT' }])[0])">添加字段</el-button>
            </div>
          </el-form-item>
          <el-form-item v-if="ocrType === 'INVOICE'" label="发票类型" required>
            <el-select v-model="invoiceType" style="width: 100%">
              <el-option v-for="item in invoiceTypes" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
        </el-form>
        <div class="upload-title required-label">识别文件</div>
        <AppUpload
          :model-value="file ? [file] : []"
          accept=".jpg,.jpeg,.png,.pdf"
          :multiple="false"
          tip="支持身份证、车牌、发票和自定义字段识别"
          :uploading="submitting"
          @update:model-value="handleFileChange"
        />
        <el-button type="primary" style="margin-top: 12px" :loading="submitting" :disabled="!canSubmit" @click="startOcr">开始识别</el-button>
        <div class="preview">
          <img v-if="isPreviewImage && activePreviewUrl" :src="activePreviewUrl" :alt="activePreviewName || '识别文件预览'" />
          <a v-else-if="activePreviewUrl" :href="activePreviewUrl" target="_blank" rel="noopener">{{ activePreviewName || '打开原文件预览' }}</a>
          <span v-else>{{ recordPreviewError || activePreviewName || '文件预览区' }}</span>
        </div>
      </el-card>

      <el-card class="work-card" v-loading="loading || submitting">
        <div class="field-head">
          <h3 class="panel-title">识别字段</h3>
          <el-button type="primary" :loading="loading" :disabled="!canSaveFields()" @click="saveFields">保存修订</el-button>
        </div>
        <EmptyState v-if="!record" description="暂无 OCR 记录，请上传文件后开始识别" />
        <template v-else>
          <div class="ocr-record-status">
            <span>{{ ocrTypeLabel(record.ocrType) }}</span>
            <StatusTag :status="record.status" />
            <span>{{ record.updatedAt }}</span>
          </div>
          <AppTable
            :data="record.fields"
            max-height="360"
            :columns="[
              { prop: 'fieldName', label: '字段' },
              { prop: 'fieldValue', label: '识别值', slot: 'fieldValue' }
            ]"
          >
            <template #empty><EmptyState description="暂无识别字段" /></template>
            <template #fieldValue="{ row }">
              <el-input v-model="row.fieldValue" :disabled="!canSaveFields()" placeholder="可修订识别值" />
            </template>
          </AppTable>
        </template>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.table-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.table-head > div { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.upload-title { margin: 4px 0 10px; font-weight: 700; }
.preview { min-height: 220px; margin-top: 14px; padding: 8px; border: 1px dashed var(--sw-border); border-radius: 12px; display: grid; place-items: center; color: var(--sw-muted); background: #f8fafc; overflow: auto; }
.preview img { display: block; max-width: 100%; max-height: 420px; width: auto; height: auto; object-fit: contain; }
.ocr-record-status { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; color: var(--sw-muted); font-size: 13px; }
.field-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
@media (max-width: 768px) {
  .table-head { align-items: flex-start; flex-direction: column; }
}
</style>
