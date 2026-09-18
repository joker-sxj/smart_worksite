export const POLICY_ARTICLE_UPDATED_AT_LABEL = '记录更新时间';
export const POLICY_ARTICLE_TIME_EXPLANATION = '发布日期来自原网页；首次入库和记录更新时间为系统时间，记录更新包含内容和入库状态变化。';

export function isLikelySingleArticleUrl(value: string): boolean {
  try {
    const url = new URL(value);
    const path = url.pathname.toLowerCase();
    if (/\/p\/\d+\/?$/.test(path) && url.hostname.toLowerCase().endsWith('zhihu.com')) return true;
    if (/(?<!\d)20\d{2}[\/_-]?(?:0[1-9]|1[0-2])[\/_-]?(?:0[1-9]|[12]\d|3[01])(?!\d)/.test(path)) return true;
    const segments = path.split('/').filter(Boolean);
    const fileName = segments[segments.length - 1] || '';
    return /^(?:content|article|detail|info)[-_]?\d+\.(?:s?html?)$/.test(fileName);
  } catch {
    return false;
  }
}

export function formatPolicyPublishDate(value?: string | null): string {
  return value?.trim() || '未提取';
}

export function formatPolicySystemTime(value?: string | null): string {
  if (!value?.trim()) return '-';
  const timestamp = new Date(value);
  return Number.isNaN(timestamp.getTime()) ? value : timestamp.toLocaleString('zh-CN', { hour12: false });
}
