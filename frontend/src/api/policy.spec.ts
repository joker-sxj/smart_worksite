import { describe, expect, it } from 'vitest';
import policyApiSource from './policy.ts?raw';
import policyViewSource from '../views/policy/PolicyInfoView.vue?raw';

describe('production policy crawler UI', () => {
  it('cannot enable a frontend mock crawler path', () => {
    expect(policyApiSource).not.toContain('VITE_USE_POLICY_MOCK');
    expect(policyApiSource).not.toContain('../mocks/policy');
    expect(policyApiSource).not.toContain('Mock 演示');
    expect(policyViewSource).not.toContain('Mock');
  });

  it('shows persisted article details including full content and provenance', () => {
    expect(policyViewSource).toContain('articleDetailVisible');
    expect(policyViewSource).toContain('完整正文');
    expect(policyViewSource).toContain('来源地址');
    expect(policyViewSource).toContain('项目 ID');
  });
});
