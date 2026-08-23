import type { ID, ReviewRecord } from '../../api/types';

const TERMINAL_STATUSES = new Set(['SUCCESS', 'COMPLETED', 'FAILED', 'ARCHIVED']);
const STORAGE_PREFIX = 'smart-worksite:review:last-record:';

export function isReviewTerminal(record: Pick<ReviewRecord, 'status'>) {
  return TERMINAL_STATUSES.has(String(record.status).toUpperCase());
}

export function reviewStorageKey(projectId: ID) {
  return `${STORAGE_PREFIX}${projectId}`;
}
