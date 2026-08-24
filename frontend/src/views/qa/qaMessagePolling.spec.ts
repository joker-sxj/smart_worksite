import { describe, expect, it } from 'vitest';
import { hasActiveQaGeneration, normalizeQaMessages, qaMessageText } from './qaMessagePolling';

const message = (status: string, extra: Record<string, unknown> = {}) => ({ status, ...extra } as never);

describe('hasActiveQaGeneration', () => {
  it('keeps polling pending and processing messages', () => {
    expect(hasActiveQaGeneration([message('PENDING'), message('SUCCESS')])).toBe(true);
    expect(hasActiveQaGeneration([message('PROCESSING')])).toBe(true);
  });

  it('stops polling when no message is active', () => {
    expect(hasActiveQaGeneration([message('SUCCESS'), message('FAILED')])).toBe(false);
  });
});

describe('qaMessageText', () => {
  it('keeps the user question visible while its assistant answer is pending', () => {
    expect(qaMessageText(message('PENDING', {
      role: 'user',
      question: '八月工地有什么安全隐患？',
      content: '八月工地有什么安全隐患？'
    }))).toBe('八月工地有什么安全隐患？');
  });

  it('shows generation status only for an active assistant message', () => {
    expect(qaMessageText(message('PROCESSING', { role: 'assistant' }))).toBe('正在生成回答，请稍候...');
  });
});

describe('normalizeQaMessages', () => {
  it('marks the synthetic user row as complete and only the assistant row as pending', () => {
    const messages = normalizeQaMessages([message('PENDING', {
      messageId: 42,
      question: '八月工地有什么安全隐患？'
    })]);

    expect(messages).toHaveLength(2);
    expect(messages[0]).toMatchObject({ role: 'user', content: '八月工地有什么安全隐患？', pending: false });
    expect(messages[1]).toMatchObject({ role: 'assistant', pending: true });
    expect(qaMessageText(messages[0])).toBe('八月工地有什么安全隐患？');
    expect(qaMessageText(messages[1])).toBe('正在生成回答，请稍候...');
  });
});
