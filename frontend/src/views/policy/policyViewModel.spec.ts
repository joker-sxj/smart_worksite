import { describe, expect, it } from 'vitest';
import {
  formatPolicyPublishDate,
  formatPolicySystemTime,
  POLICY_ARTICLE_UPDATED_AT_LABEL,
  POLICY_ARTICLE_TIME_EXPLANATION,
  isLikelySingleArticleUrl
} from './policyViewModel';

describe('policyViewModel', () => {
  it('identifies a dated article path as a single article source', () => {
    expect(isLikelySingleArticleUrl('https://example.gov.cn/policy/202609/18/notice.html')).toBe(true);
    expect(isLikelySingleArticleUrl('https://example.gov.cn/policy/2026/09/18/notice.html')).toBe(true);
    expect(isLikelySingleArticleUrl('https://example.gov.cn/policy/2026-09-18/notice.html')).toBe(true);
    expect(isLikelySingleArticleUrl('https://example.gov.cn/policy/t20260918_123456.shtml')).toBe(true);
    expect(isLikelySingleArticleUrl('  https://example.gov.cn/policy/20260918/notice.html  ')).toBe(true);
  });

  it('does not identify a policy section URL as a single article source', () => {
    expect(isLikelySingleArticleUrl('https://example.gov.cn/policy/list/index.html')).toBe(false);
  });

  it('uses source semantics when the publication date is missing', () => {
    expect(formatPolicyPublishDate(undefined)).toBe('未提取');
    expect(formatPolicyPublishDate('2026-09-17')).toBe('2026-09-17');
  });

  it('uses a neutral placeholder when a system timestamp is missing', () => {
    expect(formatPolicySystemTime(undefined)).toBe('-');
    expect(formatPolicySystemTime('')).toBe('-');
  });

  it('describes updatedAt as record update time rather than successful crawl time', () => {
    expect(POLICY_ARTICLE_UPDATED_AT_LABEL).toBe('记录更新时间');
    expect(POLICY_ARTICLE_TIME_EXPLANATION).toContain('内容和入库状态变化');
  });
});
