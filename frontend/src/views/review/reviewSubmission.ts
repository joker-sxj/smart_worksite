import type { ID } from '../../api/types';

export interface ReviewSubmission {
  projectId: ID;
  templateId: ID;
  file: File;
  referenceDocumentIds?: ID[];
  referenceFileIds?: ID[];
  referenceFiles?: File[];
}

export function buildReviewFormData(data: ReviewSubmission) {
  const form = new FormData();
  form.append('projectId', String(data.projectId));
  form.append('templateId', String(data.templateId));
  form.append('file', data.file);
  for (const id of data.referenceDocumentIds || []) form.append('referenceDocumentIds', String(id));
  for (const id of data.referenceFileIds || []) form.append('referenceFileIds', String(id));
  for (const file of data.referenceFiles || []) form.append('referenceFiles', file);
  return form;
}
