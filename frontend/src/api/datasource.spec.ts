import { describe, expect, it } from 'vitest';
import datasourceApiSource from './datasource.ts?raw';

describe('database question API timeout', () => {
  it('allows local model generation to exceed the global 15 second HTTP timeout', () => {
    expect(datasourceApiSource).toContain('DATABASE_QUERY_TIMEOUT_MS = 120_000');
    expect(datasourceApiSource).toMatch(/\/ai\/database\/query'[\s\S]*timeout:\s*DATABASE_QUERY_TIMEOUT_MS/);
  });
});
