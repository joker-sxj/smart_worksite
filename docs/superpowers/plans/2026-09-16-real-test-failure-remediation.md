# Real Test Failure Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct legacy XLS date semantics, make QA conversations open on the latest answer without stealing manual scroll position, and make compliance rule persistence unique and atomic.

**Architecture:** Keep the three fixes isolated at their current boundaries: Java document parsing, a small Vue scroll-policy helper integrated into `QaView.vue`, and Java review orchestration/persistence. Each fix starts with a regression test that reproduces the real failure, adds the minimum behavior, updates the relevant documentation, and receives an independent commit before deployment.

**Tech Stack:** Java 17, Spring Boot 3.3, Apache POI 5.2.5, JUnit 5/AssertJ, Spring transactions, Vue 3, TypeScript, Vitest, Maven, npm, MySQL 8, MinIO, SSH.

---

### Task 1: Parse Legacy Regional XLS Dates Without Guessing

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/file/infra/ExcelDocumentParser.java`
- Modify: `src/test/java/com/xd/smartworksite/file/infra/ExcelDocumentParserTest.java`
- Modify: `AGENTS.md`
- Modify: `docs/接口文档.md`

- [ ] **Step 1: Add a failing HSSF regression test**

Create an in-memory `.xls` cell with numeric value `37089`, built-in format index `58`, and assert the parsed text contains `2001-07-17（原表显示：7月17日）`, does not contain `37089.0`, and stores cell metadata with address `D4`, raw value, normalized date, source display, and format index. Add a control cell with the same number and `General` format and assert it remains numeric.

```java
@Test
void normalizesLegacyRegionalDateFormatsWithoutConvertingGeneralNumbers() throws Exception {
    try (HSSFWorkbook workbook = new HSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        Sheet sheet = workbook.createSheet("计划");
        Row row = sheet.createRow(3);
        Cell date = row.createCell(3);
        date.setCellValue(37089d);
        HSSFCellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat((short) 58);
        date.setCellStyle(dateStyle);
        row.createCell(4).setCellValue(37089d);
        workbook.write(output);
        PreparedDocument parsed = parser().parse(fileObject(...), output.toByteArray());
        assertThat(parsed.getBlocks().get(0).getText())
                .contains("2001-07-17（原表显示：7月17日）")
                .contains("37089")
                .doesNotContain("37089.0");
    }
}
```

- [ ] **Step 2: Run the focused test and verify the real failure**

Run: `mvn -Dtest=ExcelDocumentParserTest#normalizesLegacyRegionalDateFormatsWithoutConvertingGeneralNumbers test`

Expected: FAIL because format index 58 currently returns `37089.0` and no cell metadata exists.

- [ ] **Step 3: Add deterministic date formatting and metadata**

Pass workbook date-windowing state into sheet parsing. For numeric cells, regard `DateUtil.isCellDateFormatted(cell)` as authoritative; additionally recognize HSSF international date indices `27-36` and `50-58` only when the data-format string is absent. Convert with `DateUtil.getLocalDateTime(value, use1904Windowing)` and format the normalized ISO date. For Chinese regional formats, render the source display as `M月d日`; otherwise retain POI's nonblank display. Add per-cell metadata under `cells` without changing ordinary row metadata.

```java
private boolean isReliableDateCell(Cell cell) {
    int format = Short.toUnsignedInt(cell.getCellStyle().getDataFormat());
    return DateUtil.isCellDateFormatted(cell)
            || (cell instanceof HSSFCell
                && cell.getCellStyle().getDataFormatString() == null
                && ((format >= 27 && format <= 36) || (format >= 50 && format <= 58)));
}
```

Do not infer a year from the filename, current date, upload date, or neighboring cells.

- [ ] **Step 4: Run parser tests**

Run: `mvn -Dtest=ExcelDocumentParserTest test`

Expected: PASS with all legacy, sparse-sheet, CSV and date regression cases green.

- [ ] **Step 5: Document the parser contract**

Add the rule to `AGENTS.md` and `docs/接口文档.md`: spreadsheet date values must retain normalized full dates plus source display/format metadata, and unknown numeric cells must never be inferred as dates.

- [ ] **Step 6: Commit the Excel fix**

```powershell
git add AGENTS.md src/main/java/com/xd/smartworksite/file/infra/ExcelDocumentParser.java src/test/java/com/xd/smartworksite/file/infra/ExcelDocumentParserTest.java
git add -f docs/接口文档.md
git commit -m "fix: preserve legacy Excel date semantics"
```

### Task 2: Keep the Latest QA Answer Visible Respectfully

**Files:**
- Create: `frontend/src/views/qa/qaMessageScroll.ts`
- Create: `frontend/src/views/qa/qaMessageScroll.spec.ts`
- Modify: `frontend/src/views/qa/QaView.vue`
- Modify: `frontend/src/views/qa/QaView.spec.ts`
- Modify: `AGENTS.md`
- Modify: `docs/前端设计文档.md`

- [ ] **Step 1: Add failing scroll-policy tests**

Test that an empty or near-bottom viewport is sticky, a viewport scrolled well above the bottom is not, initial/session-switch/submission updates always request bottom positioning, and polling only follows when it was previously sticky.

```ts
expect(isNearMessageBottom({ scrollTop: 700, clientHeight: 300, scrollHeight: 1050 })).toBe(true);
expect(isNearMessageBottom({ scrollTop: 200, clientHeight: 300, scrollHeight: 1050 })).toBe(false);
expect(shouldFollowLatest('session-switch', false)).toBe(true);
expect(shouldFollowLatest('poll', false)).toBe(false);
```

- [ ] **Step 2: Run the focused frontend test and verify RED**

Run: `npm test -- --run src/views/qa/qaMessageScroll.spec.ts`

Expected: FAIL because `qaMessageScroll.ts` does not exist.

- [ ] **Step 3: Implement the pure policy helper**

Export `MESSAGE_BOTTOM_THRESHOLD_PX`, `isNearMessageBottom`, and `shouldFollowLatest` from `qaMessageScroll.ts`. The helper receives numeric scroll metrics only and has no DOM dependency.

- [ ] **Step 4: Run helper tests and verify GREEN**

Run: `npm test -- --run src/views/qa/qaMessageScroll.spec.ts`

Expected: PASS.

- [ ] **Step 5: Integrate the policy into `QaView.vue`**

Import `nextTick`, bind `ref="messageScroll"` to the message container, track `showLatestMessageButton`, and add `scrollToLatest(force)` which waits for DOM rendering. Capture stickiness before polling replaces messages. Force scrolling after initial load, session switch and local submission; preserve manual history reading during polling and show a compact “有新回答，回到底部” button.

- [ ] **Step 6: Add page-source contract tests**

Extend `QaView.spec.ts` to assert the container ref, `nextTick`, sticky pre-refresh capture, scroll calls after switch/submission, and the latest-message button are wired into the page.

- [ ] **Step 7: Run frontend verification**

Run: `npm test`

Expected: all Vitest suites PASS.

Run: `npm run build`

Expected: TypeScript checking and Vite production build exit 0.

- [ ] **Step 8: Document and commit the QA behavior**

Document initial/session-switch scrolling and non-disruptive polling in `AGENTS.md` and `docs/前端设计文档.md`, then commit:

```powershell
git add AGENTS.md frontend/src/views/qa/QaView.vue frontend/src/views/qa/QaView.spec.ts frontend/src/views/qa/qaMessageScroll.ts frontend/src/views/qa/qaMessageScroll.spec.ts
git add -f docs/前端设计文档.md
git commit -m "fix: open QA sessions on latest answer"
```

### Task 3: Make Review Rule IDs Unique and Result Replacement Atomic

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewRuleOrchestrator.java`
- Modify: `src/main/java/com/xd/smartworksite/review/application/ReviewRuleResultWriter.java`
- Modify: `src/test/java/com/xd/smartworksite/review/application/ReviewRuleOrchestratorTest.java`
- Modify: `src/test/java/com/xd/smartworksite/review/application/ReviewRuleResultWriterTest.java`
- Modify: `AGENTS.md`
- Modify: `src/main/java/com/xd/smartworksite/review/API.md`
- Modify: `src/main/java/com/xd/smartworksite/review/readme.md`

- [ ] **Step 1: Add a failing duplicate-number regression test**

Provide template text with two numbered `4.` rules and assert the model receives and the outcome retains `RULE-004` and `RULE-004-2`, both with their original rule names.

```java
assertThat(outcome.ruleResults())
        .extracting(ReviewRuleOrchestrator.RuleResult::ruleId)
        .containsExactly("RULE-004", "RULE-004-2");
```

- [ ] **Step 2: Verify duplicate test RED**

Run: `mvn -Dtest=ReviewRuleOrchestratorTest#assignsStableUniqueIdsToRepeatedDisplayNumbers test`

Expected: FAIL because both results currently use `RULE-004`.

- [ ] **Step 3: Implement stable occurrence suffixes and preflight validation**

Generate the base ID from display number, then maintain a parse-order occurrence map. Use the base ID once and append `-2`, `-3`, and so on. Preserve the display number separately in each rule/result map. Before the first model call, assert the final IDs are unique and throw a visible conflict if the invariant is broken.

- [ ] **Step 4: Verify orchestrator GREEN**

Run: `mvn -Dtest=ReviewRuleOrchestratorTest test`

Expected: PASS.

- [ ] **Step 5: Add failing write-integrity tests**

Extend the in-memory repository to return zero for one insert and assert `replace` fails visibly. Add a Spring transaction integration test with H2/MyBatis or a transaction-aware test repository that throws on the second insert and proves the delete plus first insert are rolled back.

- [ ] **Step 6: Verify writer tests RED**

Run: `mvn -Dtest=ReviewRuleResultWriterTest test`

Expected: FAIL because insert counts are ignored and `replace` is not transactional.

- [ ] **Step 7: Make replacement atomic and observable**

Annotate `replace` with `@Transactional`, require each insert to affect exactly one row, and reject duplicate IDs before deleting existing rows. Keep the database unique constraint unchanged and do not use upsert.

- [ ] **Step 8: Verify review tests GREEN**

Run: `mvn -Dtest=ReviewRuleOrchestratorTest,ReviewRuleResultWriterTest,ReviewApplicationServiceTest test`

Expected: PASS.

- [ ] **Step 9: Document and commit the review contract**

Document unique internal rule IDs, retained display numbering and atomic replacement, then commit:

```powershell
git add AGENTS.md src/main/java/com/xd/smartworksite/review src/test/java/com/xd/smartworksite/review
git commit -m "fix: persist repeated review rules atomically"
```

### Task 4: Full Verification, Review, Deployment and Real-Machine Acceptance

**Files:**
- Create: `docs/superpowers/reports/2026-09-16-real-test-failure-remediation-acceptance.md`
- Modify: `.trellis/tasks/09-16-real-test-failures/implement.md`
- Modify: `.trellis/tasks/09-16-real-test-failures/task.json`

- [ ] **Step 1: Run full local gates**

Run: `mvn clean test`

Expected: exit 0 with zero failures/errors.

Run: `npm test` from `frontend`.

Expected: all Vitest suites PASS.

Run: `npm run build` from `frontend`.

Expected: `vue-tsc --noEmit` and Vite build exit 0.

- [ ] **Step 2: Request independent code review**

Review the three implementation commits against the approved design. Fix every Critical or Important finding, rerun affected tests, and create focused correction commits.

- [ ] **Step 3: Push and deploy the exact tested commit**

Push `main`, fast-forward `/home/xidian/sjw/smart_worksite`, rebuild/restart Java and Vue without replacing model containers, and verify backend, frontend, Python AI, MySQL, Redis, MinIO, chat, embedding and reranker health. Confirm server `HEAD` equals the pushed remote SHA.

- [ ] **Step 4: Reparse and reindex real source 519**

Use the authenticated product API/task flow to retry parsing and indexing. Inspect parse content for normalized dates and ask the original schedule questions. Record the source's 2001/2007 inconsistency instead of changing the file.

- [ ] **Step 5: Verify QA scrolling in the real browser**

Open an existing long conversation, switch between sessions, send a question, scroll upward while polling, and use the return-to-bottom button. Capture screenshots and exact observed behavior.

- [ ] **Step 6: Retry review record 119 through the product flow**

First verify the record is still FAILED and partial rows exist. Use the official retry endpoint so the transactional writer replaces them; confirm terminal status, unique rule IDs and absence of duplicate-key errors. Do not update status directly in SQL.

- [ ] **Step 7: Inspect fresh logs and write acceptance evidence**

Check only the deployment/retest time window. Record commit, environment, real inputs, API/task IDs, outputs, screenshots, log findings and limitations. State that H100 proves function only and that external key rotation remains a production security action owned by the credential administrator.

- [ ] **Step 8: Commit acceptance evidence and archive Trellis task**

```powershell
git add -f docs/superpowers/reports/2026-09-16-real-test-failure-remediation-acceptance.md
git commit -m "docs: record real test failure remediation acceptance"
```

Set Trellis metadata for delivery commit and environment, archive the task, and confirm no task-specific temporary files remain.
