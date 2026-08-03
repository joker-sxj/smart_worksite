# Cross-Platform Project Lifecycle Scripts Design

Date: 2026-08-03
Status: Approved for implementation

## Goal

Provide simple, repeatable Windows and Linux commands for starting, checking, and stopping the complete Smart Worksite local development stack without exposing secrets or deleting persistent Docker data.

## User Interface

Windows PowerShell:

```powershell
.\scripts\start-all.ps1
.\scripts\status.ps1
.\scripts\stop-all.ps1
```

Linux Bash:

```bash
./scripts/start-all.sh
./scripts/status.sh
./scripts/stop-all.sh
```

Both start scripts also provide a non-destructive prerequisite/configuration check mode:

```powershell
.\scripts\start-all.ps1 -Check
```

```bash
./scripts/start-all.sh --check
```

## Responsibilities

### Start scripts

1. Resolve the repository root from the script location so execution does not depend on the current directory.
2. Check required commands: Docker Compose, Java 17+, Maven, Node.js, and npm.
3. Require `deploy/.env`; when missing, copy `deploy/.env.example` and stop with a clear message so the user can configure local values before starting.
4. Load `deploy/.env` into the Java backend process without printing secret values.
5. Start the Docker Compose stack and wait for MySQL, Redis, MinIO, and Python AI readiness.
6. Start Java and Vue as detached processes, record PID files under `logs/run/`, and redirect output to separate log files.
7. Treat existing healthy/listening services as already running and avoid duplicate Java or frontend processes.
8. Wait for ports 8015, 8080, and 5173 and verify the Python and Java health endpoints.
9. Print the frontend URL, health state, PID locations, and log locations.

### Status scripts

1. Show Docker Compose service status.
2. Report PID-file state for Java and frontend processes.
3. Check expected local ports: 5173, 8080, 8015, 13306, 16379, 19000, and 19001.
4. Probe Java `/actuator/health` and Python `/v1/health` without requiring or displaying secrets.
5. Return a non-zero status if required services are unavailable.

### Stop scripts

1. Stop only Java and frontend processes identified by project-owned PID files.
2. Validate PID ownership as far as the platform permits before terminating a process, reducing the risk from stale PID files.
3. Run Docker Compose `down` without `-v`, preserving MySQL, Redis, MinIO, and AI data volumes.
4. Remove stale project PID files after shutdown.
5. Be idempotent when services are already stopped.

## Process and Log Files

Runtime artifacts are untracked and live below the already-ignored `logs/` directory:

```text
logs/
  backend.out.log
  backend.err.log
  frontend.out.log
  frontend.err.log
  run/
    backend.pid
    frontend.pid
```

Scripts must not store environment variable values or API keys in PID or status files.

## Configuration Rules

- `deploy/.env` is the runtime configuration source for Docker Compose and the Java process launched by these scripts.
- The scripts never overwrite an existing `deploy/.env`.
- The scripts never print Qwen keys, service keys, database passwords, JWT secrets, or MinIO credentials.
- Docker Compose remains responsible for the Python AI service; the scripts do not start a second local `uvicorn` process.
- Java and frontend remain host processes to preserve fast local development behavior.

## Cross-Platform Implementation

PowerShell and Bash implementations have matching behavior and command names, but use native process-management primitives:

- Windows: `Start-Process`, `Get-Process`, `Stop-Process`, and PowerShell HTTP/TCP checks.
- Linux: `nohup`, PID files, `kill`, `/proc` or `ps` ownership checks, and `curl` or Python-based HTTP checks.

Shared behavior is documented rather than implemented through a cross-platform wrapper language, keeping first-run requirements limited to tools the project already needs.

## Error Handling

- Fail early with a direct remediation message when a required command or configuration file is missing.
- On partial startup failure, keep logs and report the failing component; do not delete data or silently claim success.
- A failure to start Java or frontend must not automatically destroy Docker volumes.
- Health-check timeouts must name the service, expected URL/port, and relevant log file.

## Documentation Changes

Update `README.md` so lifecycle scripts are the recommended local startup path. Keep manual commands as troubleshooting guidance and clarify that current Docker Compose starts the Python AI service.

## Verification

1. Run Windows check mode and confirm it does not start services.
2. Run PowerShell parser validation for all `.ps1` files.
3. Run Bash syntax validation for all `.sh` files.
4. Perform a full Windows start/status/stop cycle.
5. Verify Docker services are healthy, Java health is `UP`, Python health is `UP`, and the frontend responds on port 5173.
6. Verify login and a temporary model-QA request through the frontend proxy, then archive the temporary QA session.
7. Verify stop leaves Docker volumes intact and removes owned PID files.
8. Review output and repository diff for accidental secret exposure.

## Commit Plan

- Commit this design document.
- Implement scripts, tests/checks, and README changes.
- Run the verification checklist.
- Commit implementation with message: `feat: add cross-platform project lifecycle scripts`.
