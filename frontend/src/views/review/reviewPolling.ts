import type { ID, ReviewRecord, TaskStageLog } from '../../api/types';

const TERMINAL_STATUSES = new Set(['SUCCESS', 'COMPLETED', 'FAILED', 'CANCELED', 'ARCHIVED']);
const STORAGE_PREFIX = 'smart-worksite:review:last-record:';
const DEFAULT_RUNNING_PROGRESS = 10;
const STAGE_PROGRESS: Record<string, number> = {
  REVIEW_QUEUED: 5,
  WORKER_CLAIMED: 10,
  WORKER_HEARTBEAT: 10,
  REVIEW_EXTRACTING: 40,
  REVIEW_AI: 70,
  REVIEW_PERSISTING: 90,
  WORKER_SUCCESS: 100,
  FINISH: 100
};

export function isReviewTerminal(record: Pick<ReviewRecord, 'status'>) {
  return TERMINAL_STATUSES.has(String(record.status).toUpperCase());
}

export function reviewStorageKey(projectId: ID) {
  return `${STORAGE_PREFIX}${projectId}`;
}

export function progressFromReviewState(
  record: Pick<ReviewRecord, 'status'>,
  logs: Array<Pick<TaskStageLog, 'stageCode'>> = []
) {
  const status = String(record.status).toUpperCase();
  if (status === 'SUCCESS' || status === 'COMPLETED' || status === 'ARCHIVED') return 100;

  return logs.reduce((progress, log) => {
    const stageProgress = STAGE_PROGRESS[String(log.stageCode || '').toUpperCase()];
    return stageProgress === undefined ? progress : Math.max(progress, stageProgress);
  }, DEFAULT_RUNNING_PROGRESS);
}
