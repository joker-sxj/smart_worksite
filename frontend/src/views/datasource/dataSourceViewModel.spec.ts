import { describe, expect, it } from 'vitest';
import { buildDataSourceResultTable } from './dataSourceViewModel';

describe('database query result table model', () => {
  it('keeps the server column order and fills missing values without changing data', () => {
    const table = buildDataSourceResultTable(
      ['issue_count', 'owner'],
      [{ issue_count: 3 }, { owner: '张三', issue_count: null }]
    );

    expect(table.columns).toEqual(['issue_count', 'owner']);
    expect(table.rows).toEqual([
      { issue_count: 3, owner: null },
      { issue_count: null, owner: '张三' }
    ]);
  });

  it('uses row keys only when the backend omitted a column list', () => {
    const table = buildDataSourceResultTable([], [{ b: 2, a: 1 }]);

    expect(table.columns).toEqual(['b', 'a']);
    expect(table.rows).toEqual([{ b: 2, a: 1 }]);
  });

  it('does not expose values from keys that are not part of the declared result columns', () => {
    const table = buildDataSourceResultTable(['safe_value'], [{ safe_value: 'ok', password: 'should-not-render' }]);

    expect(table.rows).toEqual([{ safe_value: 'ok' }]);
  });
});
