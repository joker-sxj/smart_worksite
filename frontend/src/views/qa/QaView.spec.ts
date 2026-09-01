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
  qaValidityCaution
} from './QaView.vue';

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
