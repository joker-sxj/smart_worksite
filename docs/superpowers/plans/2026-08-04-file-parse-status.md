# File Parse Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make file parse records automatically progress from “待处理” to “解析中” and then “解析成功/解析失败” without a manual page refresh.

**Architecture:** Keep backend status values unchanged and add file-parse-specific presentation helpers plus a small polling controller in the frontend. `FileManagementView.vue` owns the selected file, starts polling only while its records contain `PENDING` or `RUNNING`, and disposes the timer when the file changes or the view unmounts.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vitest, Vite

---

## File Structure

- Create `frontend/src/views/file/fileParseStatus.ts`: file-parse-specific labels and active-state detection.
- Create `frontend/src/views/file/fileParseStatus.spec.ts`: unit tests for contextual labels and terminal/active states.
- Create `frontend/src/views/file/fileParsePolling.ts`: single-timer polling controller independent from Vue rendering.
- Create `frontend/src/views/file/fileParsePolling.spec.ts`: fake-timer tests for start, repeat, stop, and disposal behavior.
- Modify `frontend/src/views/file/FileManagementView.vue`: use contextual text and polling controller; clean up on file switch/unmount.
- Modify `frontend/package.json`: add the frontend unit-test command and Vitest dependency.
- Modify `frontend/package-lock.json`: lock the Vitest dependency tree.

### Task 1: Add File Parse Status Tests and Helpers

**Files:**
- Create: `frontend/src/views/file/fileParseStatus.spec.ts`
- Create: `frontend/src/views/file/fileParseStatus.ts`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

- [ ] **Step 1: Install Vitest and add the test script**

Run:

```powershell
cd frontend
npm install --save-dev vitest
npm pkg set scripts.test="vitest run"
```

Expected: `package.json` contains `"test": "vitest run"`, and `package-lock.json` records Vitest.

- [ ] **Step 2: Write the failing status helper tests**

Create `frontend/src/views/file/fileParseStatus.spec.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { fileParseStatusText, hasActiveFileParse } from './fileParseStatus';

describe('fileParseStatusText', () => {
  it.each([
    ['PENDING', '待处理'],
    ['RUNNING', '解析中'],
    ['SUCCESS', '解析成功'],
    ['FAILED', '解析失败']
  ])('maps %s to %s', (status, expected) => {
    expect(fileParseStatusText(status)).toBe(expected);
  });

  it('normalizes lowercase status and preserves unknown values', () => {
    expect(fileParseStatusText('running')).toBe('解析中');
    expect(fileParseStatusText('CUSTOM')).toBe('CUSTOM');
  });
});

describe('hasActiveFileParse', () => {
  it('returns true while any record is pending or running', () => {
    expect(hasActiveFileParse([{ status: 'SUCCESS' }, { status: 'RUNNING' }])).toBe(true);
    expect(hasActiveFileParse([{ status: 'PENDING' }])).toBe(true);
  });

  it('returns false after all records reach terminal states', () => {
    expect(hasActiveFileParse([{ status: 'SUCCESS' }, { status: 'FAILED' }])).toBe(false);
    expect(hasActiveFileParse([])).toBe(false);
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run:

```powershell
cd frontend
npm test -- src/views/file/fileParseStatus.spec.ts
```

Expected: FAIL because `fileParseStatus.ts` does not exist.

- [ ] **Step 4: Implement the minimal status helper**

Create `frontend/src/views/file/fileParseStatus.ts`:

```ts
export type FileParseStatusLike = { status?: string };

const statusText: Record<string, string> = {
  PENDING: '待处理',
  RUNNING: '解析中',
  SUCCESS: '解析成功',
  FAILED: '解析失败'
};

export function normalizeFileParseStatus(status?: string) {
  return (status || '').toUpperCase();
}

export function fileParseStatusText(status?: string) {
  const normalized = normalizeFileParseStatus(status);
  return statusText[normalized] || status || '未知';
}

export function hasActiveFileParse(records: FileParseStatusLike[]) {
  return records.some((record) => ['PENDING', 'RUNNING'].includes(normalizeFileParseStatus(record.status)));
}
```

- [ ] **Step 5: Run the focused test**

Run:

```powershell
cd frontend
npm test -- src/views/file/fileParseStatus.spec.ts
```

Expected: PASS, 8 parameterized/assertion cases covered with no failures.

- [ ] **Step 6: Commit the status helper**

```powershell
git add frontend/package.json frontend/package-lock.json frontend/src/views/file/fileParseStatus.ts frontend/src/views/file/fileParseStatus.spec.ts
git commit -m "test: cover file parse status presentation"
```

### Task 2: Add a Single-Timer Polling Controller

**Files:**
- Create: `frontend/src/views/file/fileParsePolling.spec.ts`
- Create: `frontend/src/views/file/fileParsePolling.ts`

- [ ] **Step 1: Write failing fake-timer tests**

Create `frontend/src/views/file/fileParsePolling.spec.ts`:

```ts
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createFileParsePolling } from './fileParsePolling';

afterEach(() => vi.useRealTimers());

describe('createFileParsePolling', () => {
  it('refreshes repeatedly while load reports an active parse', async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValue(true);
    const polling = createFileParsePolling(load, 2000);

    await polling.start();
    expect(load).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(2000);
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('stops scheduling after load reports terminal records', async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValueOnce(true).mockResolvedValueOnce(false);
    const polling = createFileParsePolling(load, 2000);

    await polling.start();
    await vi.advanceTimersByTimeAsync(2000);
    await vi.advanceTimersByTimeAsync(4000);

    expect(load).toHaveBeenCalledTimes(2);
  });

  it('cancels the old timer when restarted or stopped', async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValue(true);
    const polling = createFileParsePolling(load, 2000);

    await polling.start();
    await polling.start();
    polling.stop();
    await vi.advanceTimersByTimeAsync(4000);

    expect(load).toHaveBeenCalledTimes(2);
  });
});
```

- [ ] **Step 2: Run the polling test to verify it fails**

Run:

```powershell
cd frontend
npm test -- src/views/file/fileParsePolling.spec.ts
```

Expected: FAIL because `fileParsePolling.ts` does not exist.

- [ ] **Step 3: Implement the polling controller**

Create `frontend/src/views/file/fileParsePolling.ts`:

```ts
export function createFileParsePolling(load: () => Promise<boolean>, intervalMs = 2000) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let generation = 0;

  function stop() {
    generation += 1;
    if (timer) clearTimeout(timer);
    timer = undefined;
  }

  async function start() {
    stop();
    const currentGeneration = generation;
    const active = await load();
    if (!active || currentGeneration !== generation) return;

    timer = setTimeout(async () => {
      if (currentGeneration !== generation) return;
      const stillActive = await load();
      if (stillActive && currentGeneration === generation) {
        timer = setTimeout(() => void start(), intervalMs);
      }
    }, intervalMs);
  }

  return { start, stop };
}
```

Before accepting this implementation, simplify it so every active cycle schedules exactly one next request and `start()` does not increment the generation from inside a scheduled cycle. Use a private `poll(currentGeneration)` function:

```ts
export function createFileParsePolling(load: () => Promise<boolean>, intervalMs = 2000) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let generation = 0;

  function stop() {
    generation += 1;
    if (timer) clearTimeout(timer);
    timer = undefined;
  }

  async function poll(currentGeneration: number) {
    const active = await load();
    if (!active || currentGeneration !== generation) return;
    timer = setTimeout(() => void poll(currentGeneration), intervalMs);
  }

  async function start() {
    stop();
    const currentGeneration = generation;
    await poll(currentGeneration);
  }

  return { start, stop };
}
```

- [ ] **Step 4: Run the focused polling test**

Run:

```powershell
cd frontend
npm test -- src/views/file/fileParsePolling.spec.ts
```

Expected: PASS, with one timer chain and no calls after terminal state or `stop()`.

- [ ] **Step 5: Commit the polling controller**

```powershell
git add frontend/src/views/file/fileParsePolling.ts frontend/src/views/file/fileParsePolling.spec.ts
git commit -m "feat: add file parse status polling"
```

### Task 3: Integrate Contextual Status and Polling into the File View

**Files:**
- Modify: `frontend/src/views/file/FileManagementView.vue`

- [ ] **Step 1: Import lifecycle and helper dependencies**

Change the Vue import and add helper imports:

```ts
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { createFileParsePolling } from './fileParsePolling';
import { fileParseStatusText, hasActiveFileParse } from './fileParseStatus';
```

- [ ] **Step 2: Separate record loading from file selection**

Add a loader that verifies the selected file has not changed while the request was running:

```ts
async function loadSelectedFileParses() {
  const file = selected.value;
  if (!file) return false;

  try {
    const records = await fetchFileParseRecords(file.fileId, file.projectId);
    if (String(selected.value?.fileId) !== String(file.fileId)) return false;
    parses.value = records;
    parseError.value = '';
    return hasActiveFileParse(records);
  } catch (err) {
    if (String(selected.value?.fileId) !== String(file.fileId)) return false;
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    parseError.value = `解析记录加载失败，请检查后端文件解析接口。${detail}`;
    return false;
  }
}

const parsePolling = createFileParsePolling(loadSelectedFileParses);

async function selectFile(row: FileObject) {
  parsePolling.stop();
  selected.value = row;
  parses.value = [];
  parseError.value = '';
  await parsePolling.start();
}
```

- [ ] **Step 3: Restart polling after submit and retry**

Replace the direct `selectFile(row)` refresh after a successful parse submission with:

```ts
selected.value = row;
await parsePolling.start();
```

Replace the direct `selectFile(selected.value)` refresh after a successful retry with:

```ts
await parsePolling.start();
```

- [ ] **Step 4: Stop polling when selection is deleted or the view unmounts**

When deleting the selected file, clear its state:

```ts
if (String(selected.value?.fileId) === String(row.fileId)) {
  parsePolling.stop();
  selected.value = null;
  parses.value = [];
  parseError.value = '';
}
```

Add lifecycle cleanup:

```ts
onBeforeUnmount(() => parsePolling.stop());
```

- [ ] **Step 5: Render file-parse-specific text**

Change only the parse-record status slot:

```vue
<template #status="{ row }">
  <StatusTag :status="row.status" :text="fileParseStatusText(row.status)" />
</template>
```

Do not change the file-object status slot or the shared `StatusTag.vue` map.

- [ ] **Step 6: Run all frontend unit tests**

Run:

```powershell
cd frontend
npm test
```

Expected: all status and polling tests pass with zero failures.

- [ ] **Step 7: Run the production build**

Run:

```powershell
cd frontend
npm run build
```

Expected: `vue-tsc --noEmit` and `vite build` both exit with code 0.

- [ ] **Step 8: Commit the view integration**

```powershell
git add frontend/src/views/file/FileManagementView.vue
git commit -m "feat: show live file parsing status"
```

### Task 4: Browser Regression Verification

**Files:**
- No source changes expected.

- [ ] **Step 1: Confirm services are healthy**

Run:

```powershell
.\scripts\status.ps1
```

Expected: frontend, Java backend, Python AI service, MySQL, Redis, and MinIO report available.

- [ ] **Step 2: Verify the parse transition in the browser**

In the file-management page:

1. Select a parseable Word or PDF file.
2. Click “解析”.
3. Confirm the parse record initially shows “待处理” if it has not been claimed.
4. Confirm it changes automatically to “解析中” while backend status is `RUNNING`.
5. Confirm it changes automatically to “解析成功” or “解析失败” without manually refreshing or reselecting the file.
6. Confirm failed records still show the error and allow “重试解析”.

- [ ] **Step 3: Verify unrelated status labels are unchanged**

Open the task center and confirm a `RUNNING` task still displays the shared wording “运行中”, proving that the contextual override is limited to parse records.

- [ ] **Step 4: Run final repository checks**

Run:

```powershell
git diff --check
git status --short
git log --oneline -5
```

Expected: no whitespace errors; only intentional changes are present; implementation commits are visible.
