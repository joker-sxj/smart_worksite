export type OcrCustomField = {
  fieldKey: string;
  fieldName: string;
  description: string;
  required: boolean;
  valueType: 'TEXT' | 'DATE' | 'NUMBER' | 'AMOUNT' | 'BOOLEAN';
  sensitive: boolean;
};

export type OcrCustomFieldInput = Omit<Partial<OcrCustomField>, 'valueType'> & { valueType?: string };

const FIELD_KEY = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const VALUE_TYPES = new Set<OcrCustomField['valueType']>(['TEXT', 'DATE', 'NUMBER', 'AMOUNT', 'BOOLEAN']);

export function normalizeCustomFields(source: OcrCustomFieldInput[]): OcrCustomField[] {
  if (!Array.isArray(source) || source.length < 1 || source.length > 30) throw new Error('字段数量必须为 1 到 30 个');
  const keys = new Set<string>();
  const names = new Set<string>();
  return source.map((item) => {
    const fieldKey = String(item.fieldKey || '').trim();
    const fieldName = String(item.fieldName || '').trim();
    const description = String(item.description || '').trim();
    const valueType = String(item.valueType || 'TEXT').trim().toUpperCase() as OcrCustomField['valueType'];
    if (!FIELD_KEY.test(fieldKey)) throw new Error('字段编码必须以字母开头，且只能包含字母、数字和下划线');
    if (!fieldName || fieldName.length > 40) throw new Error('字段名称长度必须为 1 到 40 个字符');
    if (description.length > 200) throw new Error('字段说明不能超过 200 个字符');
    if (!VALUE_TYPES.has(valueType)) throw new Error('字段类型无效');
    if (keys.has(fieldKey.toLowerCase()) || names.has(fieldName)) throw new Error('字段编码和名称不能重复');
    keys.add(fieldKey.toLowerCase());
    names.add(fieldName);
    return { fieldKey, fieldName, description, required: Boolean(item.required), valueType, sensitive: Boolean(item.sensitive) };
  });
}

export function serializeCustomFields(fields: OcrCustomFieldInput[]) {
  return JSON.stringify(normalizeCustomFields(fields));
}
