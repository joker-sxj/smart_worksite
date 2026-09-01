import { describe, expect, it, vi } from 'vitest';
import type { QaMessage } from '../../api/types';

vi.hoisted(() => {
  const values = new Map<string, string>();
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) } });
});

vi.mock('../../api/qa', () => ({}));
vi.mock('../../api/datasource', () => ({}));
vi.mock('../../api/knowledge', () => ({}));
vi.mock('../../stores/project', () => ({}));
vi.mock('../../stores/user', () => ({}));
import {
  appendQaSubmission,
  EVIDENCE_RECOVERY_PROMPT,
  evidenceStatusMeta,
  qaEvidenceRecovery,
  qaSuggestedFollowUps,
  runSuggestedFollowUpSubmission,
  qaValidityCaution
} from './QaView.vue';
import { restoreSubmittedSuggestionKeys } from './qaMessagePolling';
import qaViewSource from './QaView.vue?raw';

function assistant(evidenceStatus: string, status = 'SUCCESS'): QaMessage & Record<string, unknown> {
  return {
    messageId: 1,
    sessionId: 11,
    projectId: 7,
    role: 'assistant',
    status,
    retrievalDiagnostics: { evidenceStatus },
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-01T08:00:00Z'
  } as QaMessage & Record<string, unknown>;
}

describe('QaView evidence recovery', () => {
  it.each([
    ['PARTIAL', '证据部分充分'],
    ['INSUFFICIENT', '证据不足'],
    ['CONFLICT', '证据存在冲突'],
    ['VALIDITY_UNKNOWN', '资料有效性未知'],
    ['RETRIEVAL_DEGRADED', '检索能力已降级'],
    ['TIMEOUT', '检索超时']
  ])('maps %s diagnostics to its Chinese evidence label', (status, label) => {
    expect(qaEvidenceRecovery(assistant(status))?.label).toBe(label);
    expect(EVIDENCE_RECOVERY_PROMPT).toBe('请在下方问题框补充地区、时间、对象或指定标准名称后重新发送。');
  });

  it('provides user-facing semantics for every actionable evidence state', () => {
    Object.values(evidenceStatusMeta).forEach((meta) => expect(meta.meaning.length).toBeGreaterThan(0));
  });

  it('does not show a recovery prompt for sufficient evidence', () => {
    expect(qaEvidenceRecovery(assistant('SUFFICIENT'))).toBeNull();
  });

  it('keeps the evidence label while exposing an independent unknown validity caution', () => {
    const message = assistant('PARTIAL');
    message.retrievalDiagnostics = { evidenceStatus: 'PARTIAL', validityStatus: 'UNKNOWN' };

    expect(qaEvidenceRecovery(message)?.label).toBe('证据部分充分');
    expect(qaValidityCaution(message)).toContain('资料有效性未知');
  });

  it('shows a small validity caution for sufficient legacy evidence', () => {
    const message = assistant('SUFFICIENT');
    message.retrievalDiagnostics = { evidenceStatus: 'SUFFICIENT', validityStatus: 'UNKNOWN' };

    expect(qaEvidenceRecovery(message)).toBeNull();
    expect(qaValidityCaution(message)).toContain('资料有效性未知');
  });

  it('keeps the previous message when adding a new question and pending answer', () => {
    const previous = { messageId: 1, answer: '上一条回答' };
    const userMessage = { messageId: 2, content: '补充后的问题' };
    const pendingMessage = { messageId: 'pending-3', pending: true };

    const messages = appendQaSubmission([previous], userMessage, pendingMessage);

    expect(messages).toEqual([previous, userMessage, pendingMessage]);
  });

  it('reads diagnostics returned by message history', () => {
    expect(qaEvidenceRecovery(assistant('CONFLICT'))?.label).toBe('证据存在冲突');
  });

  it('does not present a failed message as an evidence state', () => {
    expect(qaEvidenceRecovery(assistant('INSUFFICIENT', 'FAILED'))).toBeNull();
  });

  it('does not present a pending message as a completed evidence state', () => {
    expect(qaEvidenceRecovery({ ...assistant('PARTIAL'), pending: true })).toBeNull();
  });
});

describe('QaView persisted follow-up suggestions', () => {
  function suggestedMessage(status = 'SUCCESS', suggestionStatus = 'SUCCESS') {
    return {
      ...assistant('SUFFICIENT', status),
      suggestionStatus,
      suggestedFollowUpQuestions: ['追问一', '追问二', '追问三', '追问四']
    };
  }

  it('automatically exposes at most three persisted suggestions for a successful assistant message', () => {
    expect(qaSuggestedFollowUps(suggestedMessage())).toEqual(['追问一', '追问二', '追问三']);
  });

  it('does not expose an empty suggestion section', () => {
    expect(qaSuggestedFollowUps({ ...suggestedMessage(), suggestedFollowUpQuestions: [] })).toEqual([]);
  });

  it.each([
    ['PENDING', 'SUCCESS'],
    ['RUNNING', 'SUCCESS'],
    ['FAILED', 'SUCCESS'],
    ['SUCCESS', 'PENDING'],
    ['SUCCESS', 'FAILED']
  ])('hides suggestions for message status %s and suggestion status %s', (status, suggestionStatus) => {
    expect(qaSuggestedFollowUps(suggestedMessage(status, suggestionStatus))).toEqual([]);
  });

  it('restores suggestions from normalized polling and history records', () => {
    const restored = suggestedMessage();
    expect(qaSuggestedFollowUps(restored)).toEqual(['追问一', '追问二', '追问三']);
  });

  it('sends a clicked suggestion immediately and suppresses a duplicate click while pending', async () => {
    const state = { pending: false, submittedKeys: new Set<string>() };
    const payloads: string[] = [];
    let finish!: () => void;
    const first = runSuggestedFollowUpSubmission(state, '1:0', async () => {
      payloads.push('追问一');
      await new Promise<void>((resolve) => { finish = resolve; });
    });
    const duplicate = await runSuggestedFollowUpSubmission(state, '1:0', async () => {
      payloads.push('不应发送');
    });

    expect(payloads).toEqual(['追问一']);
    expect(duplicate).toBe(false);
    finish();
    await expect(first).resolves.toBe(true);
    expect(state.submittedKeys.has('1:0')).toBe(true);
  });

  it('uses the backend idempotency and suggestion-source contract', () => {
    expect(qaViewSource).toContain('clientRequestId:');
    expect(qaViewSource).toContain('sourceSuggestionMessageId: msg.messageId');
    expect(qaViewSource).not.toContain('idempotencyKey:');
    expect(qaViewSource).not.toContain('sourceMessageId: msg.messageId');
  });

  it('restores clicked suggestions from persisted history after refresh', () => {
    const records = [
      { messageId: 11, sourceSuggestionMessageId: 7, clientRequestId: 'suggestion-3-7-1' },
      { messageId: 12 },
      { messageId: 13, sourceSuggestionMessageId: 9, clientRequestId: 'manual-request' }
    ] as QaMessage[];

    expect([...restoreSubmittedSuggestionKeys(records)]).toEqual(['7:1']);
  });

  it('unlocks suggestion submission after a failed send', async () => {
    const state = { pending: false, submittedKeys: new Set<string>() };

    await expect(runSuggestedFollowUpSubmission(state, '1:0', async () => {
      throw new Error('send failed');
    })).rejects.toThrow('send failed');

    expect(state.pending).toBe(false);
    expect(state.submittedKeys.has('1:0')).toBe(false);
  });

  it('uses wrapping full-width controls on narrow screens', () => {
    expect(qaViewSource).toContain('class="suggestion-list"');
    expect(qaViewSource).toMatch(/\.suggestion-list\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/s);
    expect(qaViewSource).toMatch(/@media \(max-width:\s*960px\)[\s\S]*\.suggestion-button[^}]*width:\s*100%/);
  });
});
