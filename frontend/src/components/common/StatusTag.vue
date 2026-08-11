<script setup lang="ts">
const props = defineProps<{ status?: string; text?: string }>();

const typeMap: Record<string, 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
  ACTIVE: 'success',
  ENABLED: 'success',
  SUCCESS: 'success',
  COMPLETED: 'success',
  PARTIAL_SUCCESS: 'warning',
  RESOLVED: 'success',
  DONE: 'success',
  GENERATING: 'warning',
  PROCESSING: 'warning',
  INDEXING: 'warning',
  RUNNING: 'warning',
  QUEUED: 'warning',
  PENDING: 'warning',
  RETRYING: 'warning',
  OPEN: 'warning',
  FAILED: 'danger',
  ERROR: 'danger',
  IGNORED: 'info',
  DISABLED: 'info',
  ARCHIVED: 'info',
  CANCELED: 'info',
  CANCELLED: 'info'
};

const textMap: Record<string, string> = {
  ACTIVE: '正常',
  ENABLED: '启用',
  SUCCESS: '成功',
  COMPLETED: '已完成',
  PARTIAL_SUCCESS: '部分成功',
  DONE: '已完成',
  GENERATING: '生成中',
  RUNNING: '运行中',
  QUEUED: '排队中',
  RETRYING: '重试中',
  INDEXING: '入库中',
  PENDING: '待处理',
  OPEN: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已解决',
  IGNORED: '已忽略',
  FAILED: '失败',
  ERROR: '异常',
  DISABLED: '停用',
  ARCHIVED: '已归档',
  CANCELED: '已取消',
  CANCELLED: '已取消'
};
</script>

<template>
  <el-tag :type="typeMap[props.status || ''] || 'info'">
    {{ text || textMap[props.status || ''] || status || '未知' }}
  </el-tag>
</template>
