export type FileParseStatusLike = { status?: string };

const statusText: Record<string, string> = {
  PENDING: '待处理',
  PARSING: '解析中',
  RUNNING: '解析中',
  PARSED: '解析成功',
  SUCCESS: '解析成功',
  FAILED: '解析失败',
  CANCELED: '已取消'
};

export function normalizeFileParseStatus(status?: string) {
  return (status || '').toUpperCase();
}

export function fileParseStatusText(status?: string) {
  const normalized = normalizeFileParseStatus(status);
  return statusText[normalized] || status || '未知';
}

export function hasActiveFileParse(records: FileParseStatusLike[]) {
  return records.some((record) => ['PENDING', 'PARSING', 'RUNNING'].includes(normalizeFileParseStatus(record.status)));
}

export function isRetryableFileParse(record: FileParseStatusLike) {
  return ['FAILED', 'CANCELED'].includes(normalizeFileParseStatus(record.status));
}
