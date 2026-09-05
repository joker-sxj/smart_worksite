import type { ID } from '../../api/types';
import type { ReviewField } from '../../api/types';

export interface ReviewSubmission {
  projectId: ID;
  templateId: ID;
  file: File;
  referenceDocumentIds?: ID[];
  referenceFileIds?: ID[];
  referenceFiles?: File[];
  fieldValues?: Record<string, unknown>;
  schemaVersion?: number;
}

export function exceedsReviewReferenceLimit(documentIds: ID[], files: unknown[], limit = 20) {
  return new Set(documentIds.map(String)).size + files.length > limit;
}

export function buildReviewFormData(data: ReviewSubmission) {
  const form = new FormData();
  form.append('projectId', String(data.projectId));
  form.append('templateId', String(data.templateId));
  form.append('file', data.file);
  for (const id of data.referenceDocumentIds || []) form.append('referenceDocumentIds', String(id));
  for (const id of data.referenceFileIds || []) form.append('referenceFileIds', String(id));
  for (const file of data.referenceFiles || []) form.append('referenceFiles', file);
  if (data.schemaVersion != null) form.append('schemaVersion', String(data.schemaVersion));
  form.append('fieldValues', JSON.stringify(data.fieldValues || {}));
  return form;
}

export function validateReviewFieldValues(fields: ReviewField[], values: Record<string, unknown>) {
  return fields.filter((field) => field.stage === 'INPUT' && field.required)
    .filter((field) => values[field.key] == null || values[field.key] === '')
    .map((field) => field.key).concat(fields.filter((field) => field.type === 'ENUM' && values[field.key] != null && !field.options.includes(String(values[field.key]))).map((field) => field.key));
}

export function reviewFieldControl(type: ReviewField['type']) {
  if (type === 'NUMBER') return 'number';
  if (type === 'BOOLEAN') return 'switch';
  if (type === 'DATE') return 'date';
  if (type === 'TEXT') return 'textarea';
  return 'text';
}
