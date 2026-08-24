import type { QaMessage } from '../../api/types';

const ACTIVE_STATUSES = new Set(['PENDING', 'PROCESSING', 'QUEUED', 'RUNNING']);

export function hasActiveQaGeneration(messages: QaMessage[]) {
  return messages.some((message) => ACTIVE_STATUSES.has(String(message.status).toUpperCase()));
}

export function qaMessageText(message: QaMessage & Record<string, unknown>) {
  const status = String(message.status || '').toUpperCase();
  const role = String(message.role || 'assistant').toLowerCase();
  if (role === 'user') return String(message.content || message.question || '');
  if (ACTIVE_STATUSES.has(status)) return '正在生成回答，请稍候...';
  if (status === 'FAILED') return String(message.errorMessage || message.answer || message.content || '回答生成失败，请稍后重试。');
  return String(message.content || message.answer || message.question || '');
}

export function normalizeQaMessages(records: Array<QaMessage & Record<string, unknown>>) {
  const normalized: Array<QaMessage & Record<string, unknown>> = [];
  records.forEach((record) => {
    const role = String(record.role || 'assistant').toLowerCase();
    if (role === 'user') {
      normalized.push({ ...record, role: 'user', pending: false, content: qaMessageText(record) });
      return;
    }
    if (record.question && normalized[normalized.length - 1]?.role !== 'user') {
      normalized.push({
        ...record,
        messageId: `${record.messageId}-question`,
        role: 'user',
        pending: false,
        status: 'SUCCESS',
        content: record.question,
        answer: undefined,
        references: [],
        needClarification: false,
        clarificationQuestions: []
      });
    }
    const normalizedStatus = String(record.status || '').toUpperCase();
    normalized.push({
      ...record,
      role: 'assistant',
      pending: ACTIVE_STATUSES.has(normalizedStatus),
      content: record.answer || record.content || ''
    });
  });
  return normalized;
}
