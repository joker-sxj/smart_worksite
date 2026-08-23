export const DEFAULT_UPLOAD_MAX_SIZE_MB = 100;

function parseAccept(accept: string) {
  return accept.split(',').map((item) => item.trim().toLowerCase()).filter(Boolean);
}

function isAllowedType(file: File, accept: string) {
  const rules = parseAccept(accept);
  if (!rules.length) return true;
  const name = file.name.toLowerCase();
  const mime = file.type.toLowerCase();
  return rules.some((rule) => {
    if (rule.startsWith('.')) return name.endsWith(rule);
    if (rule.endsWith('/*')) return mime.startsWith(rule.slice(0, -1));
    return mime === rule;
  });
}

export function validateUploadFile(file: File, maxSizeMb: number, accept = '') {
  const maxSizeBytes = maxSizeMb * 1024 * 1024;
  if (file.size > maxSizeBytes) {
    const measuredSizeMb = (file.size / 1024 / 1024).toFixed(2);
    return `文件 ${file.name} 大小为 ${measuredSizeMb}MB，超过 ${maxSizeMb}MB 限制`;
  }
  if (!isAllowedType(file, accept)) return `文件 ${file.name} 类型不符合要求`;
  return '';
}
