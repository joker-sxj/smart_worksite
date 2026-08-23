import { describe, expect, it } from 'vitest';
import { isReviewTerminal, reviewStorageKey } from './reviewPolling';

describe('review polling', () => {
  it('recognizes terminal states', () => {
    expect(isReviewTerminal({ status: 'COMPLETED' } as never)).toBe(true);
    expect(isReviewTerminal({ status: 'FAILED' } as never)).toBe(true);
    expect(isReviewTerminal({ status: 'PROCESSING' } as never)).toBe(false);
  });

  it('isolates persisted records by project', () => {
    expect(reviewStorageKey(1)).not.toBe(reviewStorageKey(2));
  });
});
