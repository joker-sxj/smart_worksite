import type { FileParseRecord } from '../../api/file';
import type { ID } from '../../api/types';
import { hasActiveFileParse } from '../file/fileParseStatus';

export type DocumentParseRecords = Record<string, FileParseRecord>;

export function documentParseRecord(records: DocumentParseRecords, documentId: ID) {
  return records[String(documentId)];
}

export function setDocumentParseRecord(records: DocumentParseRecords, documentId: ID, record: FileParseRecord) {
  return { ...records, [String(documentId)]: record };
}

export function hasActiveDocumentParses(records: DocumentParseRecords) {
  return hasActiveFileParse(Object.values(records));
}

export function documentParseActionText(record?: FileParseRecord) {
  const status = (record?.status || '').toUpperCase();
  if (status === 'PENDING' || status === 'RUNNING') return '解析中';
  if (status === 'SUCCESS' || status === 'FAILED') return '重新解析';
  return '解析文件';
}
