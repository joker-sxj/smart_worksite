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
  return overflowY === 'visible' || overflowY === 'clip';
}
