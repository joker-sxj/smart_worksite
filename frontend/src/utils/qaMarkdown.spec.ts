import { describe, expect, it } from 'vitest';
import { renderQaMarkdown } from './qaMarkdown';

describe('renderQaMarkdown', () => {
  it('renders a standard markdown table without destroying separator rows', () => {
    const markdown = [
      '| 日期 | 位置/区域 | 隐患或问题 | 责任单位 | 来源 |',
      '| --- | --- | --- | --- | --- |',
      '| 2026-08-01 | 1号塔楼六层 | **临边防护缺口1处** | 主体劳务班组 | SI-20260801-01 |',
      '| 2026-08-01 | 地下室B1层 | 电缆敷设不规范 | 机电班组 | SI-20260801-02 |'
    ].join('\n');

    const html = renderQaMarkdown(markdown);

    expect(html).toContain('<table>');
    expect(html).toContain('<th>日期</th>');
    expect(html).toContain('<td>2026-08-01</td>');
    expect(html).toContain('<strong>临边防护缺口1处</strong>');
    expect(html).not.toContain('日期 / 位置');
  });

  it('treats a standalone horizontal rule as a block boundary', () => {
    expect(renderQaMarkdown('第一段\n\n---\n\n第二段')).toBe('<p>第一段</p><p>第二段</p>');
  });

  it('keeps malformed slash-delimited table output inside one stable table', () => {
    const markdown = [
      '日期 / 位置/区域 / 隐患或问题 / 责任单位 / 来源',
      '|',
      '|',
      ':|',
      '|',
      '| **2026-08-011号塔楼六层临边防护缺口1处主体劳务班组安全检查记录 SI-20260801-01** | | | |',
      '| ------------------------------------------------------- | ------ | ----------------------- | --------- | --------------------- |',
      '| 2026-08-01 | 地下室B1层 | **临时用电电缆敷设1处不规范**，配电箱正常 | 机电班组 | 安全检查记录 SI-20260801-02 |',
      '| 2026-08-05 | 工地现场 | **局部积水影响通行** | 资料未明确责任单位 | 每日施工日志 2026-08-05 |'
    ].join('\n');

    const html = renderQaMarkdown(markdown);

    expect(html.match(/<table>/g)).toHaveLength(1);
    expect(html).toContain('<th>日期</th>');
    expect(html).toContain('2026-08-01');
    expect(html).toContain('2026-08-05');
    expect(html).not.toContain('<p>日期 /');
    expect(html).not.toContain('<p>:|</p>');
  });
});