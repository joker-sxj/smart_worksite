import { describe, expect, it } from 'vitest';
import type { ReviewRecord } from '../../api/types';
import { createReviewRestoreGuard, deriveManualConfirmationItems, isCurrentReviewRequest, selectRestoredReviewRecord } from './reviewResultViewModel';

function record(recordId: number, createdAt: string): ReviewRecord {
  return {
    recordId,
    projectId: 1,
    templateId: 1,
    status: 'COMPLETED',
    issues: [],
    createdAt,
    updatedAt: createdAt
  };
}

describe('review result restoration', () => {
  const records = [record(12, '2026-09-18T10:00:00'), record(11, '2026-09-18T09:00:00')];

  it('prefers a persisted record that still exists in the project results', () => {
    expect(selectRestoredReviewRecord(records, '11')?.recordId).toBe(11);
  });

  it('falls back to the newest server record when the persisted id is invalid', () => {
    expect(selectRestoredReviewRecord(records, '99')?.recordId).toBe(12);
  });

  it('returns null when the project has no review records', () => {
    expect(selectRestoredReviewRecord([], '11')).toBeNull();
  });

  it('rejects stale restoration responses after the active project changes', () => {
    expect(isCurrentReviewRequest(2, 2, 10, 10)).toBe(true);
    expect(isCurrentReviewRequest(1, 2, 10, 10)).toBe(false);
    expect(isCurrentReviewRequest(2, 2, 10, 11)).toBe(false);
  });

  it('rejects a delayed restoration after a newer user intent', () => {
    const guard = createReviewRestoreGuard();
    const restore = guard.begin(1);

    guard.invalidate();

    expect(guard.isCurrent(restore, 1)).toBe(false);
  });
});

describe('manual confirmation projection', () => {
  it('exposes validation contradictions with their decision and evidence', () => {
    const items = deriveManualConfirmationItems({
      issues: [],
      ruleResults: [{
        ruleId: 'RULE-121',
        status: 'NEEDS_MANUAL_CONFIRMATION',
        manualConfirmationRequired: true,
        result: {
          ruleName: '临时用电审查',
          decision: 'non_compliant',
          confidence: 0.84,
          validationError: '模型判定与问题明细不一致，需人工确认',
          primaryEvidence: [{ sourceName: '施工方案', excerpt: '配电箱未标明接地方式' }]
        }
      }]
    });

    expect(items).toEqual([expect.objectContaining({
      ruleId: 'RULE-121',
      ruleName: '临时用电审查',
      decision: 'non_compliant',
      confidence: 0.84,
      reason: '模型判定与问题明细不一致，需人工确认',
      evidence: ['施工方案：配电箱未标明接地方式']
    })]);
  });

  it('exposes failed rules even when no evidence was produced', () => {
    const items = deriveManualConfirmationItems({
      issues: [],
      ruleResults: [{ ruleId: 'RULE-002', status: 'FAILED', errorMessage: '模型调用失败' }]
    });

    expect(items).toEqual([expect.objectContaining({ ruleId: 'RULE-002', reason: '模型调用失败', evidence: [] })]);
  });

  it('does not duplicate a rule that already produced an explicit issue', () => {
    const items = deriveManualConfirmationItems({
      issues: [{ issueId: 'issue-1', ruleName: '脚手架规则', severity: 'HIGH', location: '1', description: '不合规', suggestion: '整改' }],
      ruleResults: [{
        ruleId: 'RULE-003',
        status: 'NEEDS_MANUAL_CONFIRMATION',
        manualConfirmationRequired: true,
        result: { ruleName: '脚手架规则', issues: [{ issueId: 'issue-1' }] }
      }]
    });

    expect(items).toEqual([]);
  });

  it('does not let an issue from a different rule with the same name hide a manual confirmation', () => {
    const items = deriveManualConfirmationItems({
      issues: [{ issueId: 'issue-1', ruleName: '同名规则', severity: 'HIGH', location: '1', description: '不合规', suggestion: '整改' }],
      ruleResults: [{
        ruleId: 'RULE-004-2',
        status: 'NEEDS_MANUAL_CONFIRMATION',
        manualConfirmationRequired: true,
        result: { ruleName: '同名规则', validationError: '模型判定与问题明细不一致，需人工确认', issues: [] }
      }]
    });

    expect(items).toEqual([expect.objectContaining({ ruleId: 'RULE-004-2', ruleName: '同名规则' })]);
  });

  it('does not show a normally completed rule', () => {
    expect(deriveManualConfirmationItems({
      issues: [],
      ruleResults: [{ ruleId: 'RULE-004', status: 'COMPLETED', result: { decision: 'compliant' } }]
    })).toEqual([]);
  });
});
