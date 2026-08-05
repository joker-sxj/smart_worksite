<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { renderAsync } from 'docx-preview';
import readXlsxFile from 'read-excel-file/browser';
import AppTable from '../../components/common/AppTable.vue';
import AppUpload from '../../components/common/AppUpload.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import { deleteTemplate, disableTemplate, enableTemplate, fetchTemplateDetail, fetchTemplatePreview, fetchTemplateVariableDescriptions, fetchTemplates, updateTemplate, updateTemplateVariableDescriptions, uploadTemplate, type TemplateItem, type TemplateVariableDescription } from '../../api/template';
import { useProjectStore } from '../../stores/project';
import { fetchDataSources } from '../../api/datasource';
import type { DataSourceItem } from '../../api/types';
import { useUserStore } from '../../stores/user';
import { hasSuspiciousText } from '../../utils/textQuality';

const projectStore = useProjectStore();
const userStore = useUserStore();
const route = useRoute();
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const rows = ref<TemplateItem[]>([]);
const file = ref<File | null>(null);
const dialogVisible = ref(false);
const variableDialogVisible = ref(false);
const variableLoading = ref(false);
const variableSaving = ref(false);
const variableError = ref('');
const variableTemplate = ref<TemplateItem | null>(null);
const variableItems = ref<TemplateVariableDescription[]>([]);
const variableDataSources = ref<DataSourceItem[]>([]);
let variableRequestSequence = 0;
const previewVisible = ref(false);
const previewLoading = ref(false);
const previewingId = ref<string | number | null>(null);
const previewError = ref('');
const previewTitle = ref('模板预览');
const previewKind = ref<'docx' | 'table' | 'text' | 'unsupported' | ''>('');
const previewText = ref('');
const previewSheets = ref<{ name: string; rows: string[][] }[]>([]);
const activePreviewSheet = ref(0);
const docxContainer = ref<HTMLElement | null>(null);
let previewRequestSequence = 0;
const form = reactive({ templateId: '', templateName: '', templateCategory: 'REPORT', templateType: 'SAFETY_MONTHLY', scenario: '', versionNo: 'v1.0', description: '' });
const pager = reactive({ pageNo: 1, pageSize: 10, total: 0, templateCategory: '' });
const projectId = computed(() => projectStore.currentProject?.projectId);
const canManageTemplate = computed(() => userStore.hasPermission('file:manage'));
const templateManageTip = '当前账号没有模板维护权限';
const reportTypeOptions = ['SAFETY_MONTHLY', 'QUALITY_WEEKLY', 'GENERAL'];
const reviewTypeOptions = ['SAFETY_REVIEW', 'QUALITY_REVIEW', 'CONTRACT_REVIEW'];
const templateTypeOptions = computed(() => form.templateCategory === 'REPORT' ? reportTypeOptions : reviewTypeOptions);
const currentPreviewSheet = computed(() => previewSheets.value[activePreviewSheet.value]);

const templateAccept = computed(() => form.templateCategory === 'REVIEW' ? '.doc,.docx,.pdf,.xls,.xlsx,.csv,.txt,.md' : '.docx,.txt,.md');
const templateTip = computed(() => form.templateCategory === 'REVIEW'
  ? '审查模板按功能清单允许 Word、PDF、Excel/CSV；若后端无法解析会返回明确错误'
  : '报告变量解析当前支持 DOCX、TXT、MD；其他格式需后端模板变量解析扩展');

async function loadRows() {
  if (!projectId.value) {
    rows.value = [];
    pager.total = 0;
    error.value = '请先选择项目';
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const page = await fetchTemplates({ pageNo: pager.pageNo, pageSize: pager.pageSize, projectId: projectId.value, templateCategory: pager.templateCategory || undefined });
    rows.value = page.records;
    pager.total = page.total;
  } catch (err) {
    rows.value = [];
    pager.total = 0;
    error.value = err instanceof Error ? err.message : '模板列表加载失败，请检查后端模板接口。';
  } finally { loading.value = false; }
}

function readCategoryQuery() {
  return route.query.category === 'REVIEW' || route.query.category === 'REPORT'
    ? String(route.query.category)
    : '';
}

function defaultType(category: string) {
  return category === 'REPORT' ? 'SAFETY_MONTHLY' : 'SAFETY_REVIEW';
}

function openCreate(category = 'REPORT') {
  if (!canManageTemplate.value) return ElMessage.warning(templateManageTip);
  Object.assign(form, { templateId: '', templateName: '', templateCategory: category, templateType: defaultType(category), scenario: '', versionNo: 'v1.0', description: '' });
  file.value = null;
  dialogVisible.value = true;
}

async function openEdit(row: TemplateItem) {
  if (!canManageTemplate.value) return ElMessage.warning(templateManageTip);
  try {
    const detail = await fetchTemplateDetail(row.templateId);
    Object.assign(form, { templateId: String(detail.templateId), templateName: detail.templateName, templateCategory: detail.templateCategory || row.templateCategory || 'REPORT', templateType: detail.templateType, scenario: detail.scenario || '', versionNo: detail.versionNo || 'v1.0', description: detail.description || '' });
    file.value = null;
    dialogVisible.value = true;
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '模板详情加载失败，请检查后端模板详情接口。');
  }
}

async function openVariables(row: TemplateItem) {
  if (row.templateCategory !== 'REPORT') return;
  const sequence = ++variableRequestSequence;
  variableTemplate.value = row;
  variableItems.value = [];
  variableDataSources.value = [];
  variableError.value = '';
  variableDialogVisible.value = true;
  variableLoading.value = true;
  try {
    const [items, sourcePage] = await Promise.all([
      fetchTemplateVariableDescriptions(row.templateId),
      fetchDataSources({ projectId: projectId.value, status: 'ENABLED', pageNo: 1, pageSize: 100 })
    ]);
    if (sequence !== variableRequestSequence || !variableDialogVisible.value) return;
    variableItems.value = items.map((item) => ({ ...item, dataSourceIds: item.dataSourceIds || [] }));
    variableDataSources.value = sourcePage.records.filter((item) => String(item.status).toUpperCase() === 'ENABLED');
  } catch (err) {
    if (sequence !== variableRequestSequence) return;
    variableError.value = err instanceof Error ? err.message : '模板变量加载失败，请检查后端变量描述接口。';
  } finally {
    if (sequence === variableRequestSequence) variableLoading.value = false;
  }
}

function resetVariableDialog() {
  variableRequestSequence += 1;
  variableTemplate.value = null;
  variableItems.value = [];
  variableDataSources.value = [];
  variableError.value = '';
  variableLoading.value = false;
  variableSaving.value = false;
}

async function saveVariableDescriptions() {
  if (!canManageTemplate.value) return ElMessage.warning(templateManageTip);
  if (!variableTemplate.value) return;
  const emptyVariable = variableItems.value.find((item) => !item.description.trim());
  if (emptyVariable) return ElMessage.warning(`请填写变量 ${emptyVariable.variableName} 的描述`);
  const oversizedVariable = variableItems.value.find((item) => item.description.trim().length > 2000);
  if (oversizedVariable) return ElMessage.warning(`变量 ${oversizedVariable.variableName} 的描述不能超过 2000 个字符`);
  variableSaving.value = true;
  try {
    variableItems.value = await updateTemplateVariableDescriptions(
      variableTemplate.value.templateId,
      variableItems.value.map((item) => ({
        variableName: item.variableName,
        description: item.description.trim(),
        dataSourceIds: item.dataSourceIds || []
      }))
    );
    ElMessage.success('模板变量描述已保存');
    variableDialogVisible.value = false;
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '模板变量描述保存失败，请检查后端接口。');
  } finally {
    variableSaving.value = false;
  }
}

function onCategoryChange(category: string) {
  form.templateType = defaultType(category);
}

function onFilterChange() {
  pager.pageNo = 1;
  loadRows();
}

function validateForm() {
  if (!projectId.value) return '请先选择项目';
  if (!form.templateCategory) return '请选择模板分类';
  if (!form.templateName.trim()) return '请输入模板名称';
  if (!form.templateType.trim()) return '请输入模板类型';
  if (!form.versionNo.trim()) return '请输入版本号';
  if (!form.templateId && !file.value) return '请选择模板文件';
  return '';
}

function getErrorStatus(error: unknown) {
  return typeof error === 'object' && error !== null && 'response' in error
    ? (error as { response?: { status?: number } }).response?.status
    : undefined;
}

function getFileExtension(fileName: string) {
  const index = fileName.lastIndexOf('.');
  return index >= 0 ? fileName.slice(index + 1).toLowerCase() : '';
}

function formatCellValue(value: unknown) {
  if (value === null || value === undefined) return '';
  if (value instanceof Date) return value.toLocaleString('zh-CN');
  return String(value);
}

function parseCsv(text: string) {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (char === '"') {
      if (quoted && text[index + 1] === '"') {
        cell += '"';
        index += 1;
      } else {
        quoted = !quoted;
      }
    } else if (char === ',' && !quoted) {
      row.push(cell);
      cell = '';
    } else if ((char === '\n' || char === '\r') && !quoted) {
      if (char === '\r' && text[index + 1] === '\n') index += 1;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += char;
    }
  }
  if (cell || row.length) {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

function resetPreview() {
  previewError.value = '';
  previewKind.value = '';
  previewText.value = '';
  previewSheets.value = [];
  activePreviewSheet.value = 0;
  if (docxContainer.value) docxContainer.value.replaceChildren();
}

function onPreviewClosed() {
  previewRequestSequence += 1;
  previewLoading.value = false;
  previewingId.value = null;
  resetPreview();
}

async function openPreview(row: TemplateItem) {
  const sequence = ++previewRequestSequence;
  resetPreview();
  previewTitle.value = `模板预览：${row.templateName}`;
  previewVisible.value = true;
  previewLoading.value = true;
  previewingId.value = row.templateId;
  try {
    const preview = await fetchTemplatePreview(row.templateId);
    if (sequence !== previewRequestSequence || !previewVisible.value) return;
    const extension = getFileExtension(preview.fileName);
    previewTitle.value = `模板预览：${preview.fileName}`;
    if (extension === 'docx') {
      previewKind.value = 'docx';
      await nextTick();
      if (!docxContainer.value) throw new Error('模板预览容器初始化失败');
      await renderAsync(preview.blob, docxContainer.value, undefined, {
        className: 'template-docx',
        inWrapper: true,
        breakPages: true,
        useBase64URL: true
      });
    } else if (extension === 'xlsx') {
      previewKind.value = 'table';
      const sheets = await readXlsxFile(preview.blob);
      previewSheets.value = sheets.map((sheet) => ({
        name: sheet.sheet,
        rows: sheet.data.map((sheetRow) => sheetRow.map(formatCellValue))
      }));
      if (!previewSheets.value.length) throw new Error('Excel 模板中没有可预览的工作表');
    } else if (extension === 'csv') {
      previewKind.value = 'table';
      previewSheets.value = [{ name: 'CSV', rows: parseCsv(await preview.blob.text()) }];
    } else if (extension === 'txt' || extension === 'md') {
      previewKind.value = 'text';
      previewText.value = await preview.blob.text();
    } else {
      previewKind.value = 'unsupported';
      previewError.value = extension === 'doc' || extension === 'xls'
        ? `浏览器暂不支持旧版 .${extension} 文件在线解析，请转换为 ${extension === 'doc' ? 'DOCX' : 'XLSX'} 后重新上传。`
        : `暂不支持在线预览该模板格式（${extension || preview.contentType}）。`;
    }
  } catch (err) {
    if (sequence !== previewRequestSequence) return;
    previewKind.value = '';
    previewError.value = err instanceof Error ? err.message : '模板预览加载失败，请检查后端预览接口。';
  } finally {
    if (sequence === previewRequestSequence) {
      previewLoading.value = false;
      previewingId.value = null;
    }
  }
}

async function save() {
  if (!canManageTemplate.value) return ElMessage.warning(templateManageTip);
  const message = validateForm();
  if (message) return ElMessage.warning(message);
  saving.value = true;
  try {
    if (form.templateId) {
      await updateTemplate(form.templateId, {
        templateName: form.templateName.trim(),
        templateType: form.templateType.trim(),
        scenario: form.scenario.trim() || undefined,
        versionNo: form.versionNo.trim(),
        description: form.description.trim() || undefined
      });
    }
    else {
      await uploadTemplate({
        projectId: projectId.value,
        templateCategory: form.templateCategory,
        templateName: form.templateName.trim(),
        templateType: form.templateType.trim(),
        scenario: form.scenario.trim() || undefined,
        versionNo: form.versionNo.trim(),
        description: form.description.trim() || undefined,
        file: file.value as File
      });
    }
    ElMessage.success('已保存模板');
    dialogVisible.value = false;
    file.value = null;
    await loadRows();
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    const status = getErrorStatus(err);
    if (form.templateId) {
      ElMessage.error(`模板编辑失败，请检查后端模板接口。${detail}`);
    } else if (status === 500) {
      ElMessage.error(`模板上传失败，后端模板接口异常，请查看后端日志；也请检查文件存储配置。${detail}`);
    } else {
      ElMessage.error(`模板上传失败，请检查后端模板接口或文件存储配置。${detail}`);
    }
  } finally { saving.value = false; }
}

async function setStatus(row: TemplateItem, enabled: boolean) {
  if (!canManageTemplate.value) return ElMessage.warning(templateManageTip);
  try {
    enabled ? await enableTemplate(row.templateId) : await disableTemplate(row.templateId);
    ElMessage.success(enabled ? '模板已启用' : '模板已停用');
    await loadRows();
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    ElMessage.error(enabled ? `模板启用失败，请检查后端模板接口。${detail}` : `模板停用失败，请检查后端模板接口。${detail}`);
  }
}

async function remove(row: TemplateItem) {
  if (!canManageTemplate.value) return ElMessage.warning(templateManageTip);
  try {
    await ElMessageBox.confirm(`确认删除模板 ${row.templateName}？`, '删除模板', { type: 'warning' });
    await deleteTemplate(row.templateId);
    ElMessage.success('模板已删除');
    await loadRows();
  } catch (err) {
    if (err === 'cancel' || err === 'close') return;
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    ElMessage.error(`模板删除失败，请检查后端模板接口。${detail}`);
  }
}
onMounted(async () => {
  const category = readCategoryQuery();
  if (category) pager.templateCategory = category;
  if (!projectStore.currentProject) await projectStore.fetchProjects();
  await loadRows();
  if (route.query.action === 'upload' && category) openCreate(category);
});
</script>

<template>
  <div class="page">
    <div class="page-header"><div><h2 class="page-title">模板中心</h2><p class="page-desc">报告模板、审查模板统一维护；兼容后续变量提取和启停删除。</p></div><div v-if="canManageTemplate"><el-button @click="openCreate('REVIEW')">上传审查模板</el-button><el-button type="primary" @click="openCreate('REPORT')">上传报告模板</el-button></div></div>
    <el-card class="work-card">
      <template #header><div class="table-head"><strong>模板列表</strong><el-select v-model="pager.templateCategory" clearable placeholder="模板分类" style="width:160px" @change="onFilterChange"><el-option label="报告模板" value="REPORT" /><el-option label="审查模板" value="REVIEW" /></el-select></div></template>
      <AppTable :loading="loading" :error="error" :data="rows" :total="pager.total" :page-no="pager.pageNo" :page-size="pager.pageSize" :columns="[{prop:'templateId',label:'模板 ID',width:100},{prop:'templateName',label:'模板名称',slot:'templateName'},{prop:'templateCategory',label:'分类',width:100},{prop:'templateType',label:'类型'},{prop:'versionNo',label:'版本',width:100},{prop:'status',label:'状态',slot:'status',width:110}]" @page-change="(p,s)=>{pager.pageNo=p;pager.pageSize=s;loadRows()}">
        <template #empty><EmptyState description="暂无模板，可先上传报告模板或审查模板。" /></template>
        <template #templateName="{ row }">
          <span>{{ row.templateName }}</span>
          <el-tag v-if="hasSuspiciousText(row.templateName)" type="warning" size="small" style="margin-left: 6px">疑似历史乱码数据</el-tag>
        </template>
        <template #status="{ row }"><StatusTag :status="row.status" :text="row.status === 'ACTIVE' ? 'ENABLED' : row.status" /></template>
        <el-table-column label="操作" width="370"><template #default="{ row }"><el-button link type="primary" :loading="previewingId === row.templateId" @click="openPreview(row)">预览</el-button><el-button v-if="row.templateCategory === 'REPORT'" link type="primary" @click="openVariables(row)">模板变量</el-button><el-button link @click="openEdit(row)">编辑</el-button><el-button link type="primary" @click="setStatus(row, row.status === 'DISABLED')">{{ row.status === 'DISABLED' ? '启用' : '停用' }}</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
      </AppTable>
    </el-card>
    <el-dialog v-model="variableDialogVisible" :title="`模板变量：${variableTemplate?.templateName || ''}`" width="760px" top="8vh" destroy-on-close @closed="resetVariableDialog">
      <div v-loading="variableLoading" class="variable-dialog-body">
        <el-alert v-if="variableError" :title="variableError" type="error" show-icon :closable="false" />
        <el-table v-else-if="variableItems.length" :data="variableItems" border stripe max-height="58vh">
          <el-table-column label="变量名" width="260">
            <template #default="{ row }"><el-input :model-value="row.variableName" disabled /></template>
          </el-table-column>
          <el-table-column label="变量描述" min-width="300">
            <template #default="{ row }">
              <el-input v-model="row.description" type="textarea" :rows="2" maxlength="2000" show-word-limit :disabled="!canManageTemplate" placeholder="请输入该变量的业务含义和生成要求" />
            </template>
          </el-table-column>
          <el-table-column label="限定数据源" min-width="260">
            <template #default="{ row }">
              <el-select v-model="row.dataSourceIds" multiple collapse-tags clearable style="width: 100%" :disabled="!canManageTemplate" placeholder="留空则由 AI 自动选择">
                <el-option v-for="source in variableDataSources" :key="source.dataSourceId" :label="source.name" :value="source.dataSourceId" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState v-else-if="!variableLoading && !variableError" description="该报告模板没有可配置的变量。" />
      </div>
      <template #footer>
        <el-button @click="variableDialogVisible = false">关闭</el-button>
        <el-button v-if="canManageTemplate" type="primary" :loading="variableSaving" :disabled="variableLoading || !!variableError || !variableItems.length" @click="saveVariableDescriptions">保存变量描述</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="previewVisible" :title="previewTitle" width="86vw" top="5vh" destroy-on-close @closed="onPreviewClosed">
      <div v-loading="previewLoading" class="preview-shell">
        <el-alert v-if="previewError" :title="previewError" type="error" show-icon :closable="false" />
        <div v-show="previewKind === 'docx'" ref="docxContainer" class="docx-container" />
        <template v-if="previewKind === 'table' && currentPreviewSheet">
          <div v-if="previewSheets.length > 1" class="sheet-selector">
            <span>工作表</span>
            <el-select v-model="activePreviewSheet" style="width: 220px">
              <el-option v-for="(sheet, index) in previewSheets" :key="`${sheet.name}-${index}`" :label="sheet.name" :value="index" />
            </el-select>
          </div>
          <div class="table-preview">
            <table>
              <tbody>
                <tr v-for="(sheetRow, rowIndex) in currentPreviewSheet.rows" :key="rowIndex">
                  <td v-for="(cell, cellIndex) in sheetRow" :key="cellIndex">{{ cell }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
        <pre v-if="previewKind === 'text'" class="text-preview">{{ previewText }}</pre>
      </div>
      <template #footer><el-button @click="previewVisible = false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="dialogVisible" title="模板信息" width="620px">
      <el-form label-width="96px">
        <el-form-item label="模板名称" required><el-input v-model="form.templateName" /></el-form-item>
        <el-form-item label="模板分类" required><el-select v-model="form.templateCategory" :disabled="!!form.templateId" @change="onCategoryChange"><el-option label="报告模板" value="REPORT" /><el-option label="审查模板" value="REVIEW" /></el-select></el-form-item>
        <el-form-item label="模板类型" required>
          <el-select v-model="form.templateType" filterable allow-create default-first-option>
            <el-option v-for="item in templateTypeOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用场景"><el-input v-model="form.scenario" /></el-form-item>
        <el-form-item label="版本号" required><el-input v-model="form.versionNo" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" /></el-form-item>
        <el-form-item v-if="!form.templateId" label="模板文件" required><AppUpload :model-value="file ? [file] : []" :accept="templateAccept" :multiple="false" :max-size-mb="50" :tip="templateTip" @update:model-value="file = $event[0] || null" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-head { display:flex; justify-content:space-between; align-items:center; }
.variable-dialog-body { min-height: 220px; }
.preview-shell { min-height: 320px; max-height: 76vh; overflow: auto; padding: 12px; background: #f4f7fb; border-radius: 6px; }
.docx-container { min-height: 280px; }
.sheet-selector { position: sticky; top: -12px; z-index: 2; display: flex; gap: 12px; align-items: center; padding: 10px 0; background: #f4f7fb; }
.table-preview { overflow: auto; background: #fff; border: 1px solid #dcdfe6; }
.table-preview table { min-width: 100%; border-collapse: collapse; table-layout: auto; }
.table-preview td { min-width: 120px; max-width: 360px; padding: 8px 10px; border: 1px solid #ebeef5; color: #303133; white-space: pre-wrap; word-break: break-word; }
.table-preview tr:first-child td { font-weight: 600; background: #f5f7fa; }
.text-preview { min-height: 280px; margin: 0; padding: 20px; overflow: auto; color: #303133; font: 14px/1.7 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; background: #fff; border: 1px solid #dcdfe6; }
:deep(.docx-wrapper) { padding: 24px; background: #e8edf3; }
:deep(.docx-wrapper > section.template-docx) { margin-bottom: 20px; box-shadow: 0 2px 12px rgb(0 0 0 / 12%); }
</style>
