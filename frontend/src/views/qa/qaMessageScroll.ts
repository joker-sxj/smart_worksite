export const MESSAGE_BOTTOM_THRESHOLD_PX = 80;

export interface MessageScrollMetrics {
  scrollTop: number;
  clientHeight: number;
  scrollHeight: number;
}

export type LatestMessageReason = 'initial-load' | 'session-switch' | 'submission' | 'poll' | 'send-complete';

export function isNearMessageBottom(
  metrics: MessageScrollMetrics,
  threshold = MESSAGE_BOTTOM_THRESHOLD_PX
) {
  return metrics.scrollHeight - metrics.clientHeight - metrics.scrollTop <= threshold;
}

export function shouldFollowLatest(reason: LatestMessageReason, wasNearBottom: boolean) {
  return reason === 'initial-load' || reason === 'session-switch' || reason === 'submission' || wasNearBottom;
}

export function shouldUsePageScroll(overflowY: string) {
  return !['auto', 'scroll', 'overlay'].includes(overflowY.trim().toLowerCase());
}

export function findScrollableAncestor<T extends { parentElement: T | null }>(
  start: T | null,
  overflowY: (node: T) => string
) {
  let current = start;
  while (current) {
    if (!shouldUsePageScroll(overflowY(current))) return current;
    current = current.parentElement;
  }
  return null;
}
