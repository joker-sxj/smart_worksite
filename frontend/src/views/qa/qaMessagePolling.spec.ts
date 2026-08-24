import { describe, expect, it } from 'vitest';
import { hasActiveQaGeneration, qaMessageText } from './qaMessagePolling';

const message = (status: string, extra: Record<string, unknown> = {}) => ({ status, ...extra } as never);

describe('hasActiveQaGeneration', () => {
  it('keeps polling pending and processing messages', () => {
    expect(hasActiveQaGeneration([message('PENDING')])).toBe(true);
    expect(hasActiveQaGeneration([message('PROCESSING')])).toBe(true);
  });

  it('stops polling terminal messages', () => {
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
