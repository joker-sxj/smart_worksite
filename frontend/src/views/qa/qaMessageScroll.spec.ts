import { describe, expect, it } from 'vitest';
import {
  isNearMessageBottom,
  nextMessageScrollIntent,
  shouldFollowLatest,
  shouldUsePageScroll,
  type MessageScrollIntent
} from './qaMessageScroll';

describe('QA message scrolling policy', () => {
  it('treats a short viewport and a viewport within the threshold as sticky', () => {
    expect(isNearMessageBottom({ scrollTop: 0, clientHeight: 400, scrollHeight: 300 })).toBe(true);
    expect(isNearMessageBottom({ scrollTop: 700, clientHeight: 300, scrollHeight: 1050 })).toBe(true);
  });

  it('keeps reading intent through repeated async refreshes', () => {
    let intent: MessageScrollIntent = 'FOLLOWING_LATEST';
    intent = nextMessageScrollIntent(intent, 'user-away-from-bottom');
    intent = nextMessageScrollIntent(intent, 'poll');
    intent = nextMessageScrollIntent(intent, 'poll');
    intent = nextMessageScrollIntent(intent, 'send-complete');

    expect(intent).toBe('READING_HISTORY');
    expect(shouldFollowLatest(intent)).toBe(false);
  });

  it.each(['user-near-bottom', 'return-to-latest', 'session-switch', 'submission'] as const)(
    'restores following intent after %s',
    (event) => expect(nextMessageScrollIntent('READING_HISTORY', event)).toBe('FOLLOWING_LATEST')
  );

  it('keeps following intent through polling updates', () => {
    expect(nextMessageScrollIntent('FOLLOWING_LATEST', 'poll')).toBe('FOLLOWING_LATEST');
    expect(shouldFollowLatest('FOLLOWING_LATEST')).toBe(true);
  });

  it('uses page metrics when responsive CSS disables the message viewport scroll', () => {
    expect(shouldUsePageScroll('visible')).toBe(true);
    expect(shouldUsePageScroll('auto')).toBe(false);
    expect(shouldUsePageScroll('scroll')).toBe(false);
  });
});
