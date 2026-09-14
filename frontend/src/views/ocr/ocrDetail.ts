import type { OcrField, OcrRecord } from '../../api/types';

export function ocrRuntimeMeta(record: OcrRecord) {
  const summary = record.rawResult?.summary;
  const source = summary && typeof summary === 'object' ? summary as Record<string, unknown> : {};
  return {
    provider: String(source.provider || source.ocrProvider || ''),
    model: String(source.model || source.ocrModel || ''),
    semanticProvider: String(source.semanticProvider || ''),
    semanticModel: String(source.semanticModel || '')
  };
}

export function confidenceLabel(confidence?: number | null) {
  if (confidence === undefined || confidence === null || Number.isNaN(Number(confidence))) return '未提供';
  return `${(Number(confidence) * 100).toFixed(1)}%`;
}

export function fieldLocationLabel(field: OcrField) {
  if (field.location) return field.pageNo ? `${field.location} / 第${field.pageNo}页` : field.location;
  return field.pageNo ? `第${field.pageNo}页` : '未定位';
}

export function fieldReviewLabel(field: OcrField) {
  if (field.manualConfirmationRequired) return '需人工确认';
  if (field.revised) return '已修订';
  return '自动识别';
}

export function canConfirmOcrRecord(record: OcrRecord) {
  return ['SUCCESS', 'PARTIAL_SUCCESS'].includes(String(record.status || '').toUpperCase())
    && !record.manuallyConfirmed
    && !(record.fields || []).some((field) => field.manualConfirmationRequired);
}

function invoiceExtras(record: OcrRecord) {
  const raw = record.rawResult && typeof record.rawResult === 'object' ? record.rawResult as Record<string, unknown> : {};
  const extras = raw.extras && typeof raw.extras === 'object' ? raw.extras as Record<string, unknown> : {};
  return extras;
}

export function invoiceItems(record: OcrRecord) {
  const items = invoiceExtras(record).items;
  return Array.isArray(items) ? items.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object')) : [];
}

export function invoiceValidation(record: OcrRecord) {
  const validation = invoiceExtras(record).validation;
  return validation && typeof validation === 'object' ? validation as Record<string, unknown> : {};
}
