import type { FileParseContent, FileParseRecord } from '../../api/file';
import type { KnowledgeDocument } from '../../api/types';

const CONTENT_PREVIEW_LIMIT = 6000;
const BLOCK_SUMMARY_LIMIT = 180;
const BLOCK_DISPLAY_LIMIT = 20;

export interface DocumentBlockSummary {
  blockId: string;
  type: string;
  location: string;
  summary: string;
}

export interface DocumentMetadataView {
  formats: { effective?: string; declared?: string; detected?: string };
  blocks: DocumentBlockSummary[];
  totalBlocks?: number;
}

export interface DocumentDetailView {
  document: KnowledgeDocument;
  parseRecord?: FileParseRecord;
  fileFormat: string;
  contentType: string;
  parserProvider: string;
  parserModel: string;
  resultFormat: string;
  contentPreview: string;
  contentTruncated: boolean;
  metadata: DocumentMetadataView;
  previewUrl?: string;
  downloadUrl?: string;
}

function recordOf(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function scalarText(value: unknown): string | undefined {
  if (typeof value === 'string' && value.trim()) return value.trim();
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return undefined;
}

export function truncatePreview(value: string | undefined, limit = CONTENT_PREVIEW_LIMIT): string {
  const text = value || '';
  if (text.length <= limit) return text;
  return `${text.slice(0, Math.max(0, limit))}…`;
}

function blockLocation(block: Record<string, unknown>): string {
  const location = recordOf(block.location) || {};
  const page = scalarText(location.page ?? location.pageNumber ?? block.page ?? block.pageNumber);
  const sheet = scalarText(location.sheet ?? location.sheetName ?? block.sheet ?? block.sheetName);
  const slide = scalarText(location.slide ?? location.slideNumber ?? block.slide ?? block.slideNumber);
  const cellRange = scalarText(location.cellRange ?? block.cellRange);
  const parts: string[] = [];
  if (page) parts.push(`第 ${page} 页`);
  if (slide) parts.push(`幻灯片 ${slide}`);
  if (sheet) parts.push(`Sheet ${sheet}`);
  if (cellRange) parts.push(cellRange);
  return parts.join(' / ') || '-';
}

function blockSummary(block: Record<string, unknown>): string {
  const direct = scalarText(block.text) || scalarText(block.summary) || scalarText(block.caption);
  if (direct) return truncatePreview(direct, BLOCK_SUMMARY_LIMIT);
  const structured = recordOf(block.structuredData);
  if (!structured) return '-';
  try {
    return truncatePreview(JSON.stringify(structured), BLOCK_SUMMARY_LIMIT);
  } catch {
    return '-';
  }
}

export function parseDocumentMetadata(raw: string | undefined): DocumentMetadataView {
  if (!raw) return { formats: {}, blocks: [] };
  try {
    const metadata = recordOf(JSON.parse(raw));
    if (!metadata) return { formats: {}, blocks: [] };
    const sourceBlocks = Array.isArray(metadata.blocks) ? metadata.blocks : [];
    const blocks = sourceBlocks
      .slice(0, BLOCK_DISPLAY_LIMIT)
      .map(recordOf)
      .filter((block): block is Record<string, unknown> => Boolean(block))
      .map((block, index) => ({
        blockId: scalarText(block.blockId) || `block-${index + 1}`,
        type: scalarText(block.type) || 'UNKNOWN',
        location: blockLocation(block),
        summary: blockSummary(block)
      }));
    const formats = {
      effective: scalarText(metadata.effectiveFormat),
      declared: scalarText(metadata.declaredFormat),
      detected: scalarText(metadata.detectedFormat)
    };
    const compactFormats = Object.fromEntries(Object.entries(formats).filter(([, value]) => value)) as DocumentMetadataView['formats'];
    return { formats: compactFormats, blocks, totalBlocks: sourceBlocks.length || undefined };
  } catch {
    return { formats: {}, blocks: [] };
  }
}

export function selectSuccessfulContentRecord(
  latest?: FileParseRecord,
  latestSuccessful?: FileParseRecord
): FileParseRecord | undefined {
  if (latest && ['SUCCESS', 'PARSED'].includes(String(latest.status).toUpperCase())) return latest;
  return latestSuccessful;
}

export function buildDocumentDetailView(
  document: KnowledgeDocument,
  parseRecord?: FileParseRecord,
  parseContent?: FileParseContent,
  access: { preview?: string; download?: string } = {}
): DocumentDetailView {
  const rawContent = parseContent?.content || parseRecord?.contentPreview || '';
  return {
    document,
    parseRecord,
    fileFormat: document.fileExt || '-',
    contentType: document.contentType || parseRecord?.sourceContentType || '-',
    parserProvider: parseRecord?.parserProvider || '-',
    parserModel: parseRecord?.parserModel || '-',
    resultFormat: parseContent?.resultFormat || parseRecord?.resultFormat || '-',
    contentPreview: truncatePreview(rawContent),
    contentTruncated: rawContent.length > CONTENT_PREVIEW_LIMIT,
    metadata: parseDocumentMetadata(parseRecord?.metadata),
    previewUrl: access.preview,
    downloadUrl: access.download
  };
}
