import type { QaMessage } from '../../api/types';

const ACTIVE_STATUSES = new Set(['PENDING', 'PROCESSING', 'QUEUED', 'RUNNING']);

export function hasActiveQaGeneration(messages: QaMessage[]) {
  return messages.some((message) => ACTIVE_STATUSES.has(String(message.status).toUpperCase()));
}

export function qaMessageText(message: QaMessage & Record<string, unknown>) {
  const status = String(message.status || '').toUpperCase();
  const role = String(message.role || 'assistant').toLowerCase();
  if (role === 'user') return String(message.content || message.question || '');
  if (['PENDING', 'PROCESSING', 'QUEUED', 'RUNNING'].includes(status)) return '正在生成回答，请稍候...';
  if (status === 'FAILED') return String(message.errorMessage || message.answer || message.content || '回答生成失败，请稍后重试。');
  return String(message.content || message.answer || message.question || '');
}
