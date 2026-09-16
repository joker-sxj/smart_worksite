import { describe, expect, it } from 'vitest';
import { isNearMessageBottom, shouldFollowLatest, shouldUsePageScroll } from './qaMessageScroll';

describe('QA message scrolling policy', () => {
  it('treats a short viewport and a viewport within the threshold as sticky', () => {
    expect(isNearMessageBottom({ scrollTop: 0, clientHeight: 400, scrollHeight: 300 })).toBe(true);
    expect(isNearMessageBottom({ scrollTop: 700, clientHeight: 300, scrollHeight: 1050 })).toBe(true);
  });

  it('preserves the position when the user is reading older messages', () => {
    expect(isNearMessageBottom({ scrollTop: 200, clientHeight: 300, scrollHeight: 1050 })).toBe(false);
    expect(shouldFollowLatest('poll', false)).toBe(false);
    expect(shouldFollowLatest('send-complete', false)).toBe(false);
  });

  it.each(['initial-load', 'session-switch', 'submission'] as const)(
    'always follows the latest message after %s',
    (reason) => expect(shouldFollowLatest(reason, false)).toBe(true)
  );

  it('continues following polling updates when the user was already at the bottom', () => {
    expect(shouldFollowLatest('poll', true)).toBe(true);
  });

  it('uses page metrics when responsive CSS disables the message viewport scroll', () => {
    expect(shouldUsePageScroll('visible')).toBe(true);
    expect(shouldUsePageScroll('auto')).toBe(false);
    expect(shouldUsePageScroll('scroll')).toBe(false);
  });
});
