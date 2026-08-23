import { describe, expect, it } from 'vitest';
import { hasActiveQaGeneration } from './qaMessagePolling';

const message = (status: string) => ({ status } as never);

describe('hasActiveQaGeneration', () => {
  it('keeps polling pending and processing messages', () => {
    expect(hasActiveQaGeneration([message('PENDING')])).toBe(true);
    expect(hasActiveQaGeneration([message('PROCESSING')])).toBe(true);
  });

  it('stops polling terminal messages', () => {
    expect(hasActiveQaGeneration([message('SUCCESS'), message('FAILED')])).toBe(false);
  });
});
