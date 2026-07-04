import { expect, test } from '@playwright/test';

test('home page initial load stays within local smoke threshold', async ({ page }) => {
  const startedAt = Date.now();
  await page.goto('/', { waitUntil: 'networkidle' });
  const elapsedMs = Date.now() - startedAt;
  expect(elapsedMs).toBeLessThan(5000);
});
