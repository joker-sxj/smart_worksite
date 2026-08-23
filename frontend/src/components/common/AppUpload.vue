<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Loading } from '@element-plus/icons-vue';
import { ElMessage, type UploadFile, type UploadFiles, type UploadProps, type UploadUserFile } from 'element-plus';
import { DEFAULT_UPLOAD_MAX_SIZE_MB, validateUploadFile } from './uploadValidation';

const props = withDefaults(defineProps<{
  modelValue?: File[];
  accept?: string;
  maxSizeMb?: number;
  tip?: string;
  disabled?: boolean;
  uploading?: boolean;
  error?: string;
  multiple?: boolean;
}>(), { maxSizeMb: DEFAULT_UPLOAD_MAX_SIZE_MB, tip: '', multiple: true });

const emit = defineEmits<{ 'update:modelValue': [files: File[]]; change: [files: File[]] }>();
const fileList = ref<UploadUserFile[]>([]);

const extensionLabels: Record<string, string> = {
  '.doc': 'Word',
  '.docx': 'Word',
  '.pdf': 'PDF',
  '.xls': 'Excel',
  '.xlsx': 'Excel',
  '.ppt': 'PPT',
  '.pptx': 'PPT',
  '.jpg': '图片',
  '.jpeg': '图片',
  '.png': '图片',
  '.webp': '图片',
  '.txt': 'TXT',
  '.md': 'Markdown'
};

function parseAccept() {
  return (props.accept || '').split(',').map((item) => item.trim().toLowerCase()).filter(Boolean);
}

const displayTip = computed(() => {
  if (props.tip) return props.tip;
  const labels = Array.from(new Set(parseAccept().map((rule) => extensionLabels[rule] || rule.toUpperCase())));
  return labels.length ? `支持 ${labels.join('、')} 文件` : '仅允许上传当前业务配置的文件类型';
});

function validateFile(file: File) {
  return validateUploadFile(file, props.maxSizeMb, props.accept || '');
}

const beforeUpload: UploadProps['beforeUpload'] = (rawFile) => {
  const message = validateFile(rawFile);
  if (message) {
    ElMessage.error(message);
    return false;
  }
  return true;
};

function onChange(_file: UploadFile, files: UploadFiles) {
  syncValidFiles(files);
}

function onRemove(_file: UploadFile, files: UploadFiles) {
  syncValidFiles(files);
}

function onExceed(files: File[]) {
  const next = files[0];
  if (!next) return;
  
  const message = validateFile(next);
  if (message) {
    ElMessage.error(message);
    return;
  }
  const item = {
    name: next.name,
    size: next.size,
    raw: next,
    uid: Date.now(),
    status: 'ready'
  } as UploadUserFile;
  fileList.value = [item];
  emit('update:modelValue', [next]);
  emit('change', [next]);
}

function syncValidFiles(files: UploadFiles) {
  const validItems: UploadUserFile[] = [];
  const validRawFiles: File[] = [];
  for (const item of files) {
    if (!item.raw) continue;
    const message = validateFile(item.raw);
    if (message) {
      ElMessage.error(message);
      continue;
    }
    validItems.push(item);
    validRawFiles.push(item.raw);
  }
  if (!props.multiple && validRawFiles.length > 1) {
    ElMessage.error('当前操作只允许选择一个文件');
    fileList.value = [];
    emit('update:modelValue', []);
    emit('change', []);
    return;
  }
  fileList.value = validItems;
  emit('update:modelValue', validRawFiles);
  emit('change', validRawFiles);
}

watch(() => props.modelValue, (files) => {
  if (!files?.length) fileList.value = [];
}, { deep: true });
</script>

<template>
  <div>
    <el-upload v-model:file-list="fileList" drag :multiple="multiple" :limit="multiple ? undefined : 1" :auto-upload="false" :accept="accept" :disabled="disabled || uploading" :before-upload="beforeUpload" @change="onChange" @remove="onRemove" @exceed="onExceed">
      <div class="upload-icon">+</div>
      <div class="el-upload__text">拖拽文件到此处，或 <em>点击上传</em></div>
      <template #tip><div class="el-upload__tip">{{ displayTip }}，单文件不超过 {{ maxSizeMb }}MB</div></template>
    </el-upload>
    <div v-if="uploading" class="upload-state"><el-icon class="is-loading"><Loading /></el-icon> 上传处理中...</div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" style="margin-top: 10px" />
  </div>
</template>

<style scoped>
.upload-icon { font-size: 34px; color: var(--sw-primary); line-height: 1; }
.upload-state { margin-top: 10px; color: var(--sw-muted); display: flex; align-items: center; gap: 6px; }
</style>
