import type { ID, ReviewIssue, ReviewRecord, ReviewRuleResult } from '../../api/types';

export interface ManualConfirmationItem {
  ruleId: string;
  ruleName: string;
  status: string;
  decision: string;
  confidence?: number;
  reason: string;
  evidence: string[];
  raw: ReviewRuleResult;
}

interface ReviewResultSource {
  issues?: ReviewIssue[];
  ruleResults?: ReviewRuleResult[];
}

export function selectRestoredReviewRecord(records: ReviewRecord[], persistedRecordId?: ID | null) {
  if (!records.length) return null;
  const persisted = persistedRecordId == null
    ? undefined
    : records.find((record) => String(record.recordId) === String(persistedRecordId));
  return persisted || records[0];
}

export function isCurrentReviewRequest(
  requestGeneration: number,
  activeGeneration: number,
  requestedProjectId?: ID,
  activeProjectId?: ID
) {
  return requestGeneration === activeGeneration
    && String(requestedProjectId ?? '') === String(activeProjectId ?? '');
}

export function reviewRuleResults(record: ReviewRecord | null): ReviewRuleResult[] {
  if (!record) return [];
  if (Array.isArray(record.ruleResults)) return record.ruleResults;
  return Array.isArray(record.result?.ruleResults) ? record.result.ruleResults as ReviewRuleResult[] : [];
}

export function deriveManualConfirmationItems(source: ReviewResultSource): ManualConfirmationItem[] {
  const explicitIssueIds = new Set((source.issues || []).map((issue) => String(issue.issueId)));

  return (source.ruleResults || []).flatMap((rule) => {
    const result = asRecord(rule.result);
    const nestedIssues = Array.isArray(result.issues) ? result.issues.map(asRecord) : [];
    const hasExplicitIssue = nestedIssues.some((issue) => {
      const issueId = issue.issueId == null ? '' : String(issue.issueId).trim();
      return issueId && explicitIssueIds.has(issueId);
    });
    const status = String(rule.status || '').toUpperCase();
    const needsConfirmation = rule.manualConfirmationRequired === true
      || status === 'NEEDS_MANUAL_CONFIRMATION'
      || status === 'FAILED'
      || Boolean(result.validationError)
      || Boolean(result.error);
    if (!needsConfirmation || hasExplicitIssue) return [];

    return [{
      ruleId: rule.ruleId,
      ruleName: text(result.ruleName) || text(result.displayNumber) || rule.ruleId,
      status: rule.status,
      decision: text(result.decision) || '-',
      confidence: typeof rule.confidence === 'number'
        ? rule.confidence
        : typeof result.confidence === 'number' ? result.confidence : undefined,
      reason: text(result.validationError) || text(result.error) || rule.errorMessage || text(result.message) || '该规则需要人工确认',
      evidence: evidenceSummary(result),
      raw: rule
    }];
  });
}

function evidenceSummary(result: Record<string, unknown>) {
  return [...evidenceItems(result.primaryEvidence), ...evidenceItems(result.referenceEvidence)];
}

function evidenceItems(value: unknown): string[] {
  if (!Array.isArray(value)) return typeof value === 'string' && value.trim() ? [value.trim()] : [];
  return value.flatMap((item) => {
    if (typeof item === 'string') return item.trim() ? [item.trim()] : [];
    const evidence = asRecord(item);
    const excerpt = text(evidence.excerpt) || text(evidence.text) || text(evidence.evidence);
    if (!excerpt) return [];
    const sourceName = text(evidence.sourceName) || text(evidence.source);
    return [sourceName ? `${sourceName}：${excerpt}` : excerpt];
  });
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {};
}

function text(value: unknown) {
  return typeof value === 'string' ? value.trim() : '';
}
