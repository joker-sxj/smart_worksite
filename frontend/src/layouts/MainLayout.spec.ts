import { describe, expect, it } from 'vitest';
import source from './MainLayout.vue?raw';

describe('MainLayout responsive navigation', () => {
  it('reuses the permission-filtered menu in the desktop sidebar and mobile drawer', () => {
    expect(source.match(/v-for="group in visibleMenuGroups"/g)).toHaveLength(2);
    expect(source).toContain('aria-label="打开导航菜单"');
    expect(source).toContain('<el-drawer');
    expect(source).toContain('size="min(320px, 88vw)"');
  });

  it('closes the drawer after a menu route is selected', () => {
    expect(source).toContain('@select="handleMenuSelect"');
    expect(source).toContain('mobileDrawerVisible.value = false');
    expect(source).toContain('await router.push(path)');
  });

  it('reflows the application shell below 961px without fixed desktop widths', () => {
    expect(source).toMatch(/@media \(max-width:\s*960px\)[\s\S]*\.desktop-sidebar\s*\{[^}]*display:\s*none/);
    expect(source).toMatch(/\.mobile-menu-button\s*\{[^}]*display:\s*inline-flex/);
    expect(source).toMatch(/@media \(max-width:\s*960px\)[\s\S]*\.topbar\s*\{[^}]*flex-wrap:\s*wrap/);
    expect(source).toMatch(/@media \(max-width:\s*960px\)[\s\S]*\.project-select\s*\{[^}]*width:\s*100%/);
    expect(source).not.toContain('style="width: 240px"');
    expect(source).toMatch(/\.main-layout\s*\{[^}]*overflow:\s*hidden/);
    expect(source).toMatch(/\.content\s*\{[^}]*min-width:\s*0/);
  });
});
