# Real File Failure Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve every verified failure supplied on 2026-09-12 through independent, production-oriented fixes and real-machine acceptance.

**Architecture:** Keep Vue -> Java -> Python/local-model boundaries. Fix one root cause per commit and require automated tests, Linux deployment, Chrome verification, fresh-log inspection, direct push to `joker-sxj/smart_worksite:main`, and a desktop acceptance report before moving to the next item.

**Tech Stack:** Bash, Node.js, Java 17, Spring Boot, Apache POI, PDFBox, MyBatis, MySQL 8.4, Vue 3, Python/FastAPI, local vLLM-compatible models, Docker Compose, Chrome.

---

## Verified Facts and Non-Claims

- The three supplied XLS files are valid OLE2 workbooks. Their parse records are `PARSED`; the database-sort/status-display defect is fixed by commits `b057b11`, `d79a5d9`, and `dcda1fb`.
- `CSCEC8B-TD-M31001 事故调查报告.docx` is named DOCX but contains an OLE2 legacy Word document. Extension-only dispatch is therefore unsafe.
- `问题详细情况.docx` is a valid OOXML Word document, and `商住楼施工组织设计.doc` is a valid OLE2 Word document.
- The existing log runner forwards `SIGHUP` to managed Java/Vue process groups. On the acceptance server, closing the SSH startup session reproducibly stops both host services. This was recorded in the previous acceptance report but was not yet a scheduled implementation task before this plan.
- Existing historical acceptance reports do not prove the newly supplied files or 13 OCR images pass. Each supplied case must be re-verified.
- OCR accuracy cannot be expressed as a production percentage without authorised ground-truth labels. The supplied images can prove functional handling and field correctness only where values are independently readable.

## Ordered Delivery Queue

1. SSH-independent Java/Vue lifecycle.
2. Content-based Office format detection (approved option A).
3. All six supplied documents: parse, list/detail, ingest and evidence-based Q&A.
4. Report/review template upload using the supplied Office/PDF format variants.
5. Compliance review HTTP 500 and multi-reference PDF/Word workflow.
6. Report DOCX/PDF generation, tables, charts and evidence-consistent conclusions.
7. Q&A scrolling, new-session isolation and contextual follow-up behaviour.
8. OCR identity/passport/travel-permit/five-star-card coverage and visible fields.
9. OCR license plate, invoice, contract and custom-field coverage.

Items 3-9 retain their existing approved stage specifications. This plan adds the new failure artifacts and does not mark those stages complete from old reports.

## Task 1: Make Managed Services Survive SSH Logout

**Files:**
- Modify: `scripts/lib/run-with-log-limit.mjs`
- Modify: `scripts/log-rotation.tests.mjs`
- Test: `scripts/lifecycle-contract.tests.sh`

- [ ] **Step 1: Add a failing Linux signal-lifecycle test**

Start the log runner with a long-lived child that writes its PID, send `SIGHUP` to the runner, and assert both runner and child remain alive. Then send `SIGTERM` and assert the process group exits. Skip only the SIGHUP assertion on Windows, where that signal is unavailable.

- [ ] **Step 2: Run the test before implementation**

Run: `node scripts/log-rotation.tests.mjs`

Expected: FAIL on Linux because the current `SIGHUP` handler terminates the child.

- [ ] **Step 3: Ignore terminal hangup but preserve controlled shutdown**

Change the runner signal contract to:

```javascript
process.on('SIGHUP', () => {
  // Managed services outlive the shell that launched them.
});
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => stopChild(signal));
}
```

`SIGTERM` remains the mechanism used by `stop-all.sh`; log-write failures still terminate the child.

- [ ] **Step 4: Run lifecycle regression**

Run:

```bash
node scripts/log-rotation.tests.mjs
bash scripts/lifecycle-contract.tests.sh
mvn test
```

Expected: every command exits 0; Maven reports zero failures/errors.

- [ ] **Step 5: Commit and push**

```bash
git add scripts/lib/run-with-log-limit.mjs scripts/log-rotation.tests.mjs
git commit -m "fix: keep managed services alive after ssh logout"
git push origin HEAD:main
```

- [ ] **Step 6: Linux and Chrome acceptance**

Pull on `172.18.12.6`, run `./scripts/start-all.sh --model-profile h100-fp8.env.example` in a normal SSH command, close that SSH connection, wait at least 10 seconds, and verify from a new SSH connection that ports 8080/5173 are healthy. Refresh Chrome and verify the knowledge page renders. Check only logs created after deployment for shutdown hooks, errors or restart loops.

- [ ] **Step 7: Write the desktop report**

Create `C:\Users\23883\Desktop\SSH启动生命周期修复实机验收文档-2026-09-12.md` with commit, process IDs before/after logout, health results, Chrome result and fresh-log result.

## Task 2: Detect Office Format from Content

**Files:**
- Create: `src/main/java/com/xd/smartworksite/file/infra/DocumentFormatDetector.java`
- Create: `src/test/java/com/xd/smartworksite/file/infra/DocumentFormatDetectorTest.java`
- Modify: `src/main/java/com/xd/smartworksite/file/infra/DocumentPreparationService.java`
- Modify: `src/test/java/com/xd/smartworksite/file/infra/DocumentPreparationServiceTest.java`

- [ ] **Step 1: Add failing detector tests**

Cover valid OOXML Word/Excel/PowerPoint packages, OLE2 Word/Excel/PowerPoint streams, PDF, PNG/JPEG/WebP, unknown bytes, and extension/content mismatches. Include a test where OLE2 Word bytes have the `.docx` name and DOCX MIME type but must resolve to `DOC`.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=DocumentFormatDetectorTest,DocumentPreparationServiceTest test`

Expected: FAIL because content-based dispatch does not exist.

- [ ] **Step 3: Implement bounded content detection**

Use magic bytes and Apache POI container metadata. Do not add filename-specific conditions or a network dependency. Prefer detected format when it conflicts with extension/MIME; preserve declared and detected values for diagnostics.

- [ ] **Step 4: Verify GREEN and full regression**

Run:

```bash
mvn -Dtest=DocumentFormatDetectorTest,DocumentPreparationServiceTest test
mvn test
```

Expected: zero failures/errors.

- [ ] **Step 5: Commit, push and real-machine acceptance**

Push one commit, deploy it, upload all three supplied Word variants, parse them, inspect extracted Chinese text, database status and fresh Java/Python logs, then write `C:\Users\23883\Desktop\Office真实格式识别实机验收文档-2026-09-12.md`.

## Tasks 3-9: Existing Approved Features with New Failure Inputs

For each queue item, repeat this mandatory gate before advancing:

1. Reproduce the supplied failure and record request/task/document IDs.
2. Trace the failing Vue/Java/Python/storage/database boundary; distinguish old logs from fresh logs.
3. Write and observe a failing regression test.
4. Implement one general fix; no filename, question or expected-answer hard-coding.
5. Run focused tests, relevant suites, full Java/Python/frontend tests and production build as applicable.
6. Commit and push only that feature point to `origin/main`; verify remote `main` resolves to the tested commit.
7. Pull and deploy that exact commit to Linux.
8. Execute Chrome real-machine scenarios with real files/data/local models. Stage-level acceptance uses at least 40 non-duplicative scenarios where the function has enough meaningful variants; file-format tasks instead cover every supplied file plus positive/negative format boundaries.
9. Inspect fresh Java, Python, model, embedding, reranker, MySQL/Redis/MinIO and browser logs relevant to the feature.
10. Write a desktop acceptance report and list any unverified claim or external data constraint.

## Plan Self-Review

- Coverage: the deployment lifecycle defect and all nine remaining verified failure groups are scheduled.
- Scope: each queue item is independently testable and independently committed.
- Ambiguity: old stage reports are evidence inputs, not automatic pass results.
- Constraints: no Mock acceptance, no public-model fallback, no hard-coded sample answers, and no production accuracy percentage without ground truth.
- Known infrastructure caveat: server validation currently uses H100 hardware; it proves behaviour but not dual-A6000 throughput or capacity.
