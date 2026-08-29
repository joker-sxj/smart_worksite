const PARSEABLE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'webp', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'csv', 'ppt', 'pptx']);
const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'webp']);
const PARSEABLE_CONTENT_TYPES = new Set([
  'image/png',
  'image/jpeg',
  'image/webp',
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'text/csv',
  'application/csv',
  'application/vnd.ms-powerpoint',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation'
]);

export function fileExtension(fileName?: string) {
  const match = (fileName || '').trim().toLowerCase().match(/\.([a-z0-9]+)$/);
  return match?.[1] || '';
}

export function isParseableFileName(fileName?: string) {
  return PARSEABLE_EXTENSIONS.has(fileExtension(fileName));
}

export function isParseableFile(fileName?: string, fileExt?: string, contentType?: string) {
  const normalizedExt = (fileExt || fileExtension(fileName)).trim().toLowerCase();
  const normalizedContentType = (contentType || '').split(';', 1)[0].trim().toLowerCase();
  return PARSEABLE_EXTENSIONS.has(normalizedExt) || PARSEABLE_CONTENT_TYPES.has(normalizedContentType);
}

export function parseTargetFormatForFileName(fileName?: string) {
  return IMAGE_EXTENSIONS.has(fileExtension(fileName)) ? 'TEXT' : 'MARKDOWN';
}

export function parseTargetFormat(fileName?: string, fileExt?: string, contentType?: string) {
  const normalizedExt = (fileExt || fileExtension(fileName)).trim().toLowerCase();
  const normalizedContentType = (contentType || '').split(';', 1)[0].trim().toLowerCase();
  return IMAGE_EXTENSIONS.has(normalizedExt) || normalizedContentType.startsWith('image/') ? 'TEXT' : 'MARKDOWN';
}
