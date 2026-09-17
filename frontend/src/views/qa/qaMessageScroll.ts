export const MESSAGE_BOTTOM_THRESHOLD_PX = 80;

export interface MessageScrollMetrics {
  scrollTop: number;
  clientHeight: number;
  scrollHeight: number;
}

export type LatestMessageReason = 'initial-load' | 'session-switch' | 'submission' | 'poll' | 'send-complete';
export type MessageScrollIntent = 'FOLLOWING_LATEST' | 'READING_HISTORY';
export type MessageScrollEvent = LatestMessageReason | 'user-away-from-bottom' | 'user-near-bottom' | 'return-to-latest';

export function isNearMessageBottom(
  metrics: MessageScrollMetrics,
  threshold = MESSAGE_BOTTOM_THRESHOLD_PX
) {
  return metrics.scrollHeight - metrics.clientHeight - metrics.scrollTop <= threshold;
}

export function nextMessageScrollIntent(
  current: MessageScrollIntent,
  event: MessageScrollEvent
): MessageScrollIntent {
  if (event === 'user-away-from-bottom') return 'READING_HISTORY';
  if (event === 'user-near-bottom' || event === 'return-to-latest') return 'FOLLOWING_LATEST';
  if (event === 'initial-load' || event === 'session-switch' || event === 'submission') return 'FOLLOWING_LATEST';
  return current;
}

export function shouldFollowLatest(intent: MessageScrollIntent) {
  return intent === 'FOLLOWING_LATEST';
}

export function shouldUsePageScroll(overflowY: string) {
  return !['auto', 'scroll', 'overlay'].includes(overflowY.trim().toLowerCase());
}
