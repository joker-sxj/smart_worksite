import type { DataSourceQueryResult } from '../../api/types';

export function buildDataSourceResultTable(
  columns: DataSourceQueryResult['columns'],
  rows: DataSourceQueryResult['rows']
) {
  const resolvedColumns = columns.length > 0 ? [...columns] : Object.keys(rows[0] || {});
  return {
    columns: resolvedColumns,
    rows: rows.map((row) => Object.fromEntries(resolvedColumns.map((column) => [column, row[column] ?? null])))
  };
}
