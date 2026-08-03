# Cross-Platform Lifecycle Scripts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add safe, repeatable Windows PowerShell and Linux Bash commands to start, inspect, and stop the complete local Smart Worksite stack.

**Architecture:** Platform-specific wrappers delegate to one PowerShell helper and one Bash helper so start/status/stop behavior stays consistent without adding a new runtime dependency. Docker Compose owns infrastructure and Python AI; Java and Vue run as detached host processes with PID and log files under the ignored `logs/` directory.

**Tech Stack:** PowerShell 5.1+, Bash 4+, Docker Compose v2, Java 17, Maven, Node.js/npm, Spring Boot actuator, Vite.

---

## File Structure

- Create `scripts/lib/lifecycle.ps1`: Windows environment loading, command checks, PID validation, process launch/stop, TCP/HTTP checks, and Compose helpers.
- Create `scripts/lib/lifecycle.sh`: Linux equivalents using Bash, `nohup`, `kill`, `/proc` or `ps`, and curl/Python HTTP fallback.
- Create `scripts/start-all.ps1`, `scripts/status.ps1`, `scripts/stop-all.ps1`: PowerShell user entrypoints.
- Create `scripts/start-all.sh`, `scripts/status.sh`, `scripts/stop-all.sh`: Bash user entrypoints.
- Create `scripts/tests/lifecycle-contract.tests.ps1`: cross-platform static contract tests and PowerShell parser checks.
- Create `scripts/tests/lifecycle-contract.tests.sh`: Bash syntax and safety contract tests.
- Modify `README.md`: recommend scripts and correct the current Compose/Python startup description.

### Task 1: Add failing lifecycle script contracts

**Files:**
- Create: `scripts/tests/lifecycle-contract.tests.ps1`
- Create: `scripts/tests/lifecycle-contract.tests.sh`

- [ ] **Step 1: Write PowerShell contract tests**

The test resolves the repository root, requires all eight implementation files, parses every PowerShell script with `System.Management.Automation.Language.Parser`, asserts that stop scripts contain Compose `down`, rejects `down -v`/`--volumes`, and asserts the start scripts expose check mode.

- [ ] **Step 2: Run the PowerShell contract test and verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/lifecycle-contract.tests.ps1
```

Expected: non-zero exit because lifecycle implementation files do not exist.

- [ ] **Step 3: Write Bash contract tests**

The test requires the three Bash entrypoints and Bash helper, runs `bash -n` for each file, rejects destructive volume removal, and requires `--check` in `start-all.sh`.

- [ ] **Step 4: Run the Bash contract test and verify RED**

Run when Bash is available:

```bash
bash scripts/tests/lifecycle-contract.tests.sh
```

Expected: non-zero exit because lifecycle implementation files do not exist.

### Task 2: Implement Windows lifecycle scripts

**Files:**
- Create: `scripts/lib/lifecycle.ps1`
- Create: `scripts/start-all.ps1`
- Create: `scripts/status.ps1`
- Create: `scripts/stop-all.ps1`
- Test: `scripts/tests/lifecycle-contract.tests.ps1`

- [ ] **Step 1: Implement reusable PowerShell helpers**

Provide these functions with terminating errors for invalid configuration:

```powershell
function Get-ProjectRoot
function Get-ComposeArguments
function Import-DotEnv
function Test-RequiredCommand
function Test-TcpPort
function Wait-TcpPort
function Test-HttpHealth
function Get-OwnedProcess
function Start-ManagedProcess
function Stop-ManagedProcess
function Get-LifecycleStatus
```

`Import-DotEnv` skips comments and blank lines, splits only on the first `=`, removes matching surrounding quotes, and never prints values. `Get-OwnedProcess` validates command-line ownership with CIM before a PID is stopped.

- [ ] **Step 2: Implement `start-all.ps1`**

Support `[switch]$Check`. Normal mode checks tools/configuration, creates `logs/run`, runs Compose `up -d`, waits for the Python health endpoint, starts Maven and Vite only when their ports are not already listening, writes PID files, waits for Java/frontend, and exits non-zero on timeout with log paths.

- [ ] **Step 3: Implement `status.ps1`**

Show Compose status, managed PID state, required ports, and Java/Python health. Exit `0` only when required local services are available.

- [ ] **Step 4: Implement `stop-all.ps1`**

Stop frontend and backend through validated project PID files, then run Compose `down` without volume flags. Treat missing/stale PID files as already stopped.

- [ ] **Step 5: Run Windows contract tests and verify GREEN**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/lifecycle-contract.tests.ps1
```

Expected: `PASS` and exit code 0.

- [ ] **Step 6: Run Windows check mode**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-all.ps1 -Check
```

Expected: prerequisite/configuration summary, no new listener on 5173/8080, and exit code 0 when prerequisites exist.

### Task 3: Implement Linux lifecycle scripts

**Files:**
- Create: `scripts/lib/lifecycle.sh`
- Create: `scripts/start-all.sh`
- Create: `scripts/status.sh`
- Create: `scripts/stop-all.sh`
- Test: `scripts/tests/lifecycle-contract.tests.sh`

- [ ] **Step 1: Implement reusable Bash helpers**

Use `set -euo pipefail` and provide matching helpers for root resolution, dotenv export, command checks, TCP/HTTP waits, PID ownership validation, detached process launch, managed stop, and Compose invocation. HTTP checks use `curl` when available and Python 3 otherwise.

- [ ] **Step 2: Implement `start-all.sh`**

Support `--check`; start Compose, wait for Python AI, start `mvn spring-boot:run` and `npm run dev` via `nohup`, record PIDs, wait for health/ports, and print URLs/logs.

- [ ] **Step 3: Implement `status.sh` and `stop-all.sh`**

Mirror PowerShell status and stop semantics. `stop-all.sh` sends TERM, waits, uses KILL only after timeout, runs Compose `down`, and never passes `-v` or `--volumes`.

- [ ] **Step 4: Make Bash scripts executable**

Run:

```bash
chmod +x scripts/start-all.sh scripts/status.sh scripts/stop-all.sh scripts/tests/lifecycle-contract.tests.sh
```

- [ ] **Step 5: Run Bash tests and verify GREEN**

Run:

```bash
bash scripts/tests/lifecycle-contract.tests.sh
```

Expected: `PASS` and exit code 0.

### Task 4: Update startup documentation

**Files:**
- Modify: `README.md:170`

- [ ] **Step 1: Replace the primary local-start section**

Document Windows and Linux `start-all`, `status`, and `stop-all` commands. State that `deploy/.env` is preserved, Compose starts Python AI, logs are under `logs/`, and `stop-all` preserves volumes.

- [ ] **Step 2: Keep concise manual troubleshooting commands**

Retain manual Compose/Maven/npm commands, but require importing `deploy/.env` for Java and remove instructions that start a duplicate Python AI service when Compose is used.

- [ ] **Step 3: Run documentation safety checks**

Run:

```powershell
rg -n "start-all|status|stop-all|python-ai-service|deploy/.env" README.md
rg -n "QWEN_API_KEY=.*[^=[:space:]]" README.md scripts
```

Expected: lifecycle commands are documented and no secret value is present.

### Task 5: Full lifecycle verification

**Files:**
- Runtime only: `logs/`, Docker containers, PID files.

- [ ] **Step 1: Establish a stopped baseline**

Run `scripts/stop-all.ps1`, confirm expected ports are closed, and record Docker volume names before startup.

- [ ] **Step 2: Run the Windows start script**

Run `scripts/start-all.ps1`; expected exit 0 after Docker/Python, Java, and frontend become ready.

- [ ] **Step 3: Run status and endpoint checks**

Run `scripts/status.ps1`, direct Java health, direct Python health, and frontend HTTP checks. Expected all healthy/available.

- [ ] **Step 4: Verify login and model QA through Vite**

Log in through `http://127.0.0.1:5173/api`, create a temporary QA session, send a `MODEL` question, require a non-empty answer, and archive the temporary session.

- [ ] **Step 5: Verify idempotent start**

Run `scripts/start-all.ps1` again and confirm it reports Java/frontend already running without replacing PID files.

- [ ] **Step 6: Verify safe stop**

Run `scripts/stop-all.ps1`, verify managed ports close, PID files are removed, containers stop, and the Docker volume list is unchanged.

- [ ] **Step 7: Run repository verification**

Run contract tests, `git diff --check`, and `git status --short`. Inspect staged content for secrets before commit.

### Task 6: Commit implementation

**Files:**
- Stage all lifecycle scripts, tests, and README changes.

- [ ] **Step 1: Review the final diff**

Run:

```powershell
git diff --check
git diff -- README.md scripts
```

Expected: no whitespace errors, destructive Docker volume command, generated PID/log file, or secret.

- [ ] **Step 2: Commit**

Run:

```powershell
git add README.md scripts
git commit -m "feat: add cross-platform project lifecycle scripts"
```

Expected: commit succeeds and the worktree is clean.
