import { describe, expect, it } from 'vitest';
import { buildDocumentDetailView, parseDocumentMetadata, selectSuccessfulContentRecord, truncatePreview } from './knowledgeDocumentDetail';

describe('knowledge document detail view model', () => {
  it('safely parses format and block location metadata', () => {
    const metadata = parseDocumentMetadata(JSON.stringify({
      effectiveFormat: 'xlsx', declaredFormat: 'docx', detectedFormat: 'xlsx',
      blocks: [{ blockId: 'risk-1', type: 'TABLE', text: '一级风险', location: { sheet: '风险', cellRange: 'A2:E2' } }]
    }));

    expect(metadata.formats).toEqual({ effective: 'xlsx', declared: 'docx', detected: 'xlsx' });
    expect(metadata.blocks[0]).toMatchObject({ blockId: 'risk-1', type: 'TABLE', location: 'Sheet 风险 / A2:E2', summary: '一级风险' });
  });

  it('supports page, slide and legacy sheetName location fields', () => {
    const metadata = parseDocumentMetadata(JSON.stringify({ blocks: [
      { blockId: 'pdf', type: 'TEXT', location: { page: 3 }, text: '条款' },
      { blockId: 'ppt', type: 'TEXT', location: { slideNumber: 5 }, text: '汇报' },
      { blockId: 'xls', type: 'TABLE', location: { sheetName: '台账', cellRange: 'B3:F9' }, structuredData: { rows: 7 } }
    ] }));
    expect(metadata.blocks.map((block) => block.location)).toEqual(['第 3 页', '幻灯片 5', 'Sheet 台账 / B3:F9']);
    expect(metadata.blocks[2]?.summary).toContain('"rows":7');
  });

  it('does not throw or render unsafe arbitrary metadata', () => {
    expect(parseDocumentMetadata('{not-json}')).toEqual({ formats: {}, blocks: [] });
    expect(parseDocumentMetadata(JSON.stringify({ blocks: 'not-an-array', effectiveFormat: { bad: true } }))).toEqual({ formats: {}, blocks: [] });
  });

  it('truncates content previews to a fixed safe length', () => {
    expect(truncatePreview('abcdefgh', 5)).toBe('abcde…');
    expect(truncatePreview('abc', 5)).toBe('abc');
  });

  it('uses the latest successful record for content when the latest attempt failed', () => {
    const failed = { recordId: 9, projectId: 1, fileId: 2, status: 'FAILED' };
    const successful = { recordId: 8, projectId: 1, fileId: 2, status: 'SUCCESS' };
    expect(selectSuccessfulContentRecord(failed, successful)).toBe(successful);
    expect(selectSuccessfulContentRecord(successful, undefined)).toBe(successful);
  });

  it('builds a traceable detail model from document, parse record and content', () => {
    const model = buildDocumentDetailView(
      { documentId: 7, projectId: 1, knowledgeBaseId: 2, fileId: 9, title: '风险.xlsx', fileExt: 'xlsx', contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', indexStatus: 'SUCCESS', createdAt: '2026-09-14T01:00:00Z', updatedAt: '2026-09-14T02:00:00Z' },
      { recordId: 8, projectId: 1, fileId: 9, status: 'SUCCESS', parserProvider: 'LOCAL_DOCUMENT', parserModel: 'local-parser', resultFormat: 'MARKDOWN', progress: 100, currentStage: 'DONE', startedAt: '2026-09-14T01:01:00Z', finishedAt: '2026-09-14T01:02:00Z', createdAt: '2026-09-14T01:01:00Z', updatedAt: '2026-09-14T01:02:00Z', metadata: JSON.stringify({ effectiveFormat: 'xlsx' }) },
      { recordId: 8, resultFormat: 'MARKDOWN', content: 'content' },
      { preview: 'https://preview', download: 'https://download' }
    );

    expect(model).toMatchObject({ fileFormat: 'xlsx', contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', parserProvider: 'LOCAL_DOCUMENT', parserModel: 'local-parser', resultFormat: 'MARKDOWN', previewUrl: 'https://preview', downloadUrl: 'https://download', contentPreview: 'content' });
  });
});
