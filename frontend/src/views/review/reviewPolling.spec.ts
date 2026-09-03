import { describe, expect, it } from 'vitest';
import { isReviewTerminal, progressFromReviewState, reviewStorageKey } from './reviewPolling';

describe('review polling', () => {
  it('treats partial success as a terminal downloadable review result', () => {
    expect(isReviewTerminal({ status: 'PARTIAL_SUCCESS' })).toBe(true);
    expect(progressFromReviewState({ status: 'PARTIAL_SUCCESS' })).toBe(100);
  });
  it('recognizes terminal states', () => {
    expect(isReviewTerminal({ status: 'COMPLETED' } as never)).toBe(true);
    expect(isReviewTerminal({ status: 'FAILED' } as never)).toBe(true);
    expect(isReviewTerminal({ status: 'PROCESSING' } as never)).toBe(false);
  });

  it('isolates persisted records by project', () => {
    expect(reviewStorageKey(1)).not.toBe(reviewStorageKey(2));
  });

  it('maps persisted worker stages to truthful progress', () => {
    expect(progressFromReviewState({ status: 'PROCESSING' } as never, [])).toBe(10);
    expect(progressFromReviewState({ status: 'PROCESSING' } as never, [{ stageCode: 'REVIEW_EXTRACTING' }] as never)).toBe(40);
    expect(progressFromReviewState({ status: 'PROCESSING' } as never, [{ stageCode: 'REVIEW_AI' }] as never)).toBe(70);
    expect(progressFromReviewState({ status: 'COMPLETED' } as never, [])).toBe(100);
    expect(progressFromReviewState({ status: 'FAILED' } as never, [{ stageCode: 'REVIEW_EXTRACTING' }] as never)).toBe(40);
  });
});
