import type { FileParseRecord } from '../../api/file';
import type { ID, KnowledgeDocument } from '../../api/types';
import { hasActiveFileParse } from '../file/fileParseStatus';
import { isParseableFile, parseTargetFormat } from '../file/supportedFileParse';

export type DocumentParseRecords = Record<string, FileParseRecord>;

export function documentParseRecord(records: DocumentParseRecords, documentId: ID) {
  return records[String(documentId)];
}

export function setDocumentParseRecord(records: DocumentParseRecords, documentId: ID, record: FileParseRecord) {
  return { ...records, [String(documentId)]: record };
}

export function isDocumentParseReady(record?: FileParseRecord) {
  return ['PARSED', 'SUCCESS'].includes((record?.status || '').toUpperCase());
}

export function isKnowledgeDocumentIndexReady(indexStatus: string | undefined, record?: FileParseRecord) {
  return ['PENDING', 'FAILED'].includes((indexStatus || '').toUpperCase()) && isDocumentParseReady(record);
}

export function documentProcessingMessage(indexMessage: string | undefined, record?: FileParseRecord) {
  return record?.errorMessage?.trim() || indexMessage?.trim() || '';
}

export function hasActiveDocumentParses(records: DocumentParseRecords) {
  return hasActiveFileParse(Object.values(records));
}

export function documentParseActionText(record?: FileParseRecord) {
  const status = (record?.status || '').toUpperCase();
  if (['PENDING', 'PARSING', 'RUNNING'].includes(status)) return '解析中';
  if (['PARSED', 'SUCCESS', 'FAILED', 'CANCELED'].includes(status)) return '重新解析';
  return '解析文件';
}

export function isParseableKnowledgeDocument(document: KnowledgeDocument) {
  return Boolean(document.fileId && isParseableFile(document.title, document.fileExt, document.contentType));
}

export function knowledgeDocumentParseTargetFormat(document: KnowledgeDocument) {
  return parseTargetFormat(document.title, document.fileExt, document.contentType);
}
