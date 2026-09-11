# Production Report Chart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the misleading unlabeled report image with a business-aware, readable chart and suppress charts when no valid business dimension exists.

**Architecture:** Add a deterministic chart planner between report statistics and rendering. The planner selects an eligible business dimension and produces a chart specification; the Java2D renderer renders only that specification with labels, values, scale, unit, and source. Report generation preserves the existing DOCX/PDF pipeline and explicitly explains skipped charts.

**Tech Stack:** Java 17, Spring Boot, Apache POI, Java2D, JUnit 5, AssertJ, LibreOffice PDF conversion.

---

### Task 1: Reproduce business selection and readability defects

**Files:**
- Create: `src/main/java/com/xd/smartworksite/report/domain/ReportChartSpec.java`
- Create: `src/main/java/com/xd/smartworksite/report/application/ReportChartPlanner.java`
- Create: `src/test/java/com/xd/smartworksite/report/application/ReportChartPlannerTest.java`
- Modify: `src/test/java/com/xd/smartworksite/report/application/ReportChartRendererTest.java`

- [ ] Add failing planner tests requiring risk/status/owner priority, technical-field exclusion, placeholder exclusion, monthly trend selection, single-category eligibility, Top 10 aggregation, and explicit no-chart reasons.
- [ ] Add a failing renderer test that decodes the PNG and verifies it contains substantial non-background pixels in the title, label, scale and value regions; verify a single bar does not occupy most of the plot width.
- [ ] Run `mvn -Dtest=ReportChartPlannerTest,ReportChartRendererTest test` and confirm failures are caused by the missing planner/spec renderer.

### Task 2: Implement chart planning and readable rendering

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/report/domain/ReportChartSpec.java`
- Modify: `src/main/java/com/xd/smartworksite/report/application/ReportChartPlanner.java`
- Modify: `src/main/java/com/xd/smartworksite/report/application/ReportChartRenderer.java`

- [ ] Implement immutable `ReportChartSpec` fields for title, type, dimension, unit, values and source.
- [ ] Implement deterministic field scoring and exclusions; use monthly trend for date data and return a typed no-chart decision for invalid inputs.
- [ ] Render labeled horizontal/vertical bars or line charts with CJK font fallback, title, categories, values, scale, unit and source; cap single-category bar width.
- [ ] Run the two targeted test classes and confirm all pass.

### Task 3: Integrate with DOCX report output

**Files:**
- Modify: `src/main/java/com/xd/smartworksite/report/application/ReportStructuredContentRenderer.java`
- Modify: `src/test/java/com/xd/smartworksite/report/application/ReportStructuredContentRendererTest.java`

- [ ] Add failing tests proving technical-only and placeholder-only data produce no picture and a clear reason, while valid risk data produces one labeled picture and a descriptive caption.
- [ ] Replace first-map selection with `ReportChartPlanner`; keep chart failures bounded and visible instead of silently ignored.
- [ ] Run `mvn -Dtest=ReportStructuredContentRendererTest,ReportChartPlannerTest,ReportChartRendererTest test` and confirm all pass.

### Task 4: Regression, review, deployment and real-machine acceptance

**Files:**
- Modify: `docs/superpowers/reports/2026-09-04-stage-seven-report-enhancement-acceptance.md`
- Create: `docs/superpowers/reports/2026-09-11-stage-seven-production-chart-acceptance.md`

- [ ] Run targeted report tests, full `mvn test`, `mvn -DskipTests package`, Python tests, frontend tests/build and `git diff --check`.
- [ ] Perform one independent code review and resolve blocking/high findings.
- [ ] Commit and push the reviewed commit to `joker-sxj/smart_worksite` `main`.
- [ ] Deploy the exact pushed commit to `172.18.12.6`, generate real reports in Chrome, download Word/PDF, render every PDF page and inspect chart/table/conclusion consistency.
- [ ] Inspect browser, Java, Python AI, local model, LibreOffice, MySQL, Redis and MinIO logs for the acceptance time window.
- [ ] Record the actual report IDs, commit, inputs, outputs, scenario results, log window and remaining boundaries in the acceptance report; push the evidence update.

