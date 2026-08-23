import type { QaMessage } from '../../api/types';

const ACTIVE_STATUSES = new Set(['PENDING', 'PROCESSING', 'QUEUED', 'RUNNING']);

export function hasActiveQaGeneration(messages: QaMessage[]) {
  return messages.some((message) => ACTIVE_STATUSES.has(String(message.status).toUpperCase()));
}
