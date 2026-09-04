import { describe, expect, it } from 'vitest';
import { normalizeCustomFields, serializeCustomFields, type OcrCustomField } from './ocrCustomFields';

describe('ocrCustomFields', () => {
  it('normalizes user fields and preserves their order', () => {
    const fields = normalizeCustomFields([
      { fieldKey: 'partyA', fieldName: '甲方', valueType: 'text', required: true },
      { fieldKey: 'amount', fieldName: '金额', valueType: 'amount', sensitive: true }
    ]);
    expect(fields.map((field) => field.fieldKey)).toEqual(['partyA', 'amount']);
    expect(fields[1].valueType).toBe('AMOUNT');
  });

  it('rejects duplicate keys and unsupported field types', () => {
    expect(() => normalizeCustomFields([
      { fieldKey: 'amount', fieldName: '金额', valueType: 'AMOUNT' },
      { fieldKey: 'amount', fieldName: '总额', valueType: 'SCRIPT' }
    ])).toThrow();
  });

  it('serializes only the bounded public schema', () => {
    const field = { fieldKey: 'date', fieldName: '日期', description: '', valueType: 'DATE', required: false, sensitive: false, ignored: 'x' } as OcrCustomField;
    expect(JSON.parse(serializeCustomFields([field]))[0]).not.toHaveProperty('ignored');
  });
});
