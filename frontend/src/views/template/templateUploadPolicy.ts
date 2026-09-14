const REPORT_ACCEPT = '.docx';
const REVIEW_ACCEPT = '.doc,.docx,.pdf,.xls,.xlsx,.csv,.txt,.md';

export function templateAccept(category: string): string {
  return category === 'REVIEW' ? REVIEW_ACCEPT : REPORT_ACCEPT;
}

export function templateTip(category: string): string {
  return category === 'REVIEW'
    ? '审查模板支持 Word、PDF、Excel/CSV 和文本文件；若后端无法解析会返回明确错误'
    : '可用于生成报告的模板仅支持 DOCX 变量替换；最终报告仍可导出 Word 和 PDF';
}

export function templateTypeError(category: string): string {
  return category === 'REVIEW'
    ? ''
    : '报告模板仅支持DOCX；PDF不能作为报告模板输入，可用于审查模板和最终报告导出';
}

export function templateFileError(category: string, file: File | null | undefined): string {
  if (!file) return '';
  const extension = file.name.includes('.') ? file.name.slice(file.name.lastIndexOf('.')).toLowerCase() : '';
  if (category === 'REPORT' && extension !== '.docx') {
    return templateTypeError(category);
  }
  return '';
}
