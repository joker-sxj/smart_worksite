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

export function applyOcrCandidate(field: OcrField, candidate: { value: string; confidence?: number; evidence?: string }) {
  field.fieldValue = candidate.value;
  field.confidence = Number(candidate.confidence ?? field.confidence ?? 0);
  field.evidence = candidate.evidence || field.evidence;
  field.manualConfirmationRequired = false;
  field.confirmationReason = undefined;
  field.candidates = [];
  field.revised = true;
}

const confirmationReasonLabels: Record<string, string> = {
  LOW_CONFIDENCE: '识别置信度较低',
  MISSING_SIDE_OR_PAGE: '缺少证件背面或必要页面',
  SOURCE_MASKED: '原图内容被遮挡或打码',
  TYPE_MISMATCH: '所选识别类型与图片内容不一致',
  DUAL_PASS_CONFLICT: '两路识别结果冲突，请从候选值中确认',
  FIELD_NOT_VISIBLE: '图片中未见该字段'
};

export function fieldConfirmationReasonLabel(field: OcrField) {
  return confirmationReasonLabels[String(field.confirmationReason || '')]
    || (field.manualConfirmationRequired ? '需要人工确认' : '');
}

const documentTypeLabels: Record<string, string> = {
  ID_CARD: '身份证', PASSPORT: '护照', TRAVEL_PERMIT: '港澳台通行证',
  FIVE_STAR_CARD: '五星卡', INVOICE: '发票', CONTRACT: '合同', CUSTOM: '自定义文档', UNKNOWN: '未知'
};

export function documentTypeNotice(record: OcrRecord) {
  const raw = record.rawResult && typeof record.rawResult === 'object' ? record.rawResult as Record<string, unknown> : {};
  const extras = raw.extras && typeof raw.extras === 'object' ? raw.extras as Record<string, unknown> : {};
  const detail = extras.documentType && typeof extras.documentType === 'object'
    ? extras.documentType as Record<string, unknown> : null;
  if (!detail) return '';
  const detected = String(detail.detectedType || 'UNKNOWN');
  if (detected === 'UNKNOWN') return '文档类型无法可靠判断，请人工确认';
  if (!detail.mismatch) return '';
  const selected = String(detail.selectedType || record.ocrType || 'UNKNOWN');
  const confidence = typeof detail.confidence === 'number' ? `（${confidenceLabel(detail.confidence)}）` : '';
  return `所选类型“${documentTypeLabels[selected] || selected}”与检测类型“${documentTypeLabels[detected] || detected}”不一致${confidence}`;
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
