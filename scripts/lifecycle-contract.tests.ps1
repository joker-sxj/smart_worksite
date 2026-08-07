$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$requiredFiles = @(
    'scripts/lib/lifecycle.ps1',
    'scripts/lib/lifecycle.sh',
    'scripts/start-all.ps1',
    'scripts/status.ps1',
    'scripts/stop-all.ps1',
    'scripts/start-all.sh',
    'scripts/status.sh',
    'scripts/stop-all.sh'
)

$failures = [System.Collections.Generic.List[string]]::new()

foreach ($relativePath in $requiredFiles) {
    $fullPath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        $failures.Add("Missing required file: $relativePath")
    }
}

$powerShellFiles = $requiredFiles | Where-Object { $_.EndsWith('.ps1') }
foreach ($relativePath in $powerShellFiles) {
    $fullPath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        continue
    }

    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $fullPath,
        [ref]$tokens,
        [ref]$parseErrors
    )
    foreach ($parseError in $parseErrors) {
        $failures.Add("PowerShell parse error in ${relativePath}: $($parseError.Message)")
    }
}

$libraryPowerShell = Join-Path $repoRoot 'scripts/lib/lifecycle.ps1'
if (Test-Path -LiteralPath $libraryPowerShell) {
    . $libraryPowerShell
    function Invoke-RestMethod { return [pscustomobject]@{ data = [pscustomobject]@{ status = 'UP' } } }
    try {
        if (-not (Test-HttpHealth -Uri 'http://contract.test/health')) {
            $failures.Add('Test-HttpHealth must accept health status nested under data.status.')
        }
    } finally {
        Remove-Item Function:\Invoke-RestMethod -ErrorAction SilentlyContinue
    }
}

$libraryPowerShellContent = if (Test-Path -LiteralPath $libraryPowerShell) { Get-Content -Raw $libraryPowerShell } else { '' }
if ($libraryPowerShellContent -notmatch 'Test-TcpPort\s+-HostName\s+\$HostName\s+-Port\s+\$Port') {
    $failures.Add('Assert-ServicePortAvailable must pass its host name to Test-TcpPort.')
}

$startPowerShell = Join-Path $repoRoot 'scripts/start-all.ps1'
if ((Test-Path -LiteralPath $startPowerShell) -and (Get-Content -Raw $startPowerShell) -notmatch '\[switch\]\s*\$Check') {
    $failures.Add('scripts/start-all.ps1 must expose the -Check switch.')
}

if (Test-Path -LiteralPath $startPowerShell) {
    $startPowerShellContent = Get-Content -Raw $startPowerShell
    if ($startPowerShellContent -notmatch 'Assert-ServicePortAvailable') {
        $failures.Add('scripts/start-all.ps1 must reject occupied but unhealthy host-service ports.')
    }
    if ($startPowerShellContent -notmatch 'Test-HttpEndpoint') {
        $failures.Add('scripts/start-all.ps1 must verify that an existing frontend port serves HTTP.')
    }
    if ($startPowerShellContent -notmatch 'Wait-HttpEndpoint') {
        $failures.Add('scripts/start-all.ps1 must wait for frontend HTTP readiness instead of assuming an IPv4 listener.')
    }
    if ($startPowerShellContent -notmatch "Assert-ServicePortAvailable[^\r\n]+-HostName\s+'localhost'") {
        $failures.Add('scripts/start-all.ps1 must detect unhealthy frontend listeners bound through localhost/IPv6.')
    }
    if ($startPowerShellContent -match 'Select-Object\s+-First\s+1' -or $startPowerShellContent -notmatch 'javaVersionOutput') {
        $failures.Add('scripts/start-all.ps1 must parse the Java version from complete JVM output, after possible warnings.')
    }
    if ($startPowerShellContent -notmatch "-Arguments\s+@\('up',\s*'-d'\)") {
        $failures.Add('scripts/start-all.ps1 must pass Docker Compose detached mode without PowerShell treating -d as a script parameter.')
    }
}


if ($libraryPowerShellContent -notmatch 'MIN_FREE_DISK_MB' -or $startPowerShellContent -notmatch 'Assert-MinimumFreeDisk') {
    $failures.Add('Windows startup must reject critically low disk space before launching services.')
}

$statusPowerShell = Join-Path $repoRoot 'scripts/status.ps1'
if ((Test-Path -LiteralPath $statusPowerShell) -and (Get-Content -Raw $statusPowerShell) -notmatch 'Test-HttpEndpoint') {
    $failures.Add('scripts/status.ps1 must check frontend HTTP readiness.')
}

$startBash = Join-Path $repoRoot 'scripts/start-all.sh'
if ((Test-Path -LiteralPath $startBash) -and (Get-Content -Raw $startBash) -notmatch '--check') {
    $failures.Add('scripts/start-all.sh must expose the --check option.')
}
if ((Test-Path -LiteralPath $startBash) -and (Get-Content -Raw $startBash) -notmatch 'java_major_version') {
    $failures.Add('scripts/start-all.sh must use warning-tolerant Java version detection.')
}

foreach ($relativePath in @('scripts/stop-all.ps1', 'scripts/stop-all.sh')) {
    $fullPath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath)) {
        continue
    }
    $content = Get-Content -Raw $fullPath
    if ($content -notmatch '(?m)\bdown\b') {
        $failures.Add("$relativePath must stop Docker Compose with down.")
    }
    if ($content -match '(?m)\bdown\s+(?:[^\r\n]*\s)?(?:-v|--volumes)\b') {
        $failures.Add("$relativePath must not remove Docker volumes.")
    }
}

$logRunner = Join-Path $repoRoot 'scripts/lib/run-with-log-limit.mjs'
$logRunnerTests = Join-Path $repoRoot 'scripts/log-rotation.tests.mjs'
if (-not (Test-Path -LiteralPath $logRunner)) {
    $failures.Add('Missing bounded host log runner: scripts/lib/run-with-log-limit.mjs')
} elseif (-not (Test-Path -LiteralPath $logRunnerTests)) {
    $failures.Add('Missing bounded host log runner tests: scripts/log-rotation.tests.mjs')
} else {
    & node $logRunnerTests
    if ($LASTEXITCODE -ne 0) {
        $failures.Add('Bounded host log rotation test failed.')
    }
}

if ($libraryPowerShellContent -notmatch 'run-with-log-limit\.mjs') {
    $failures.Add('Windows managed processes must use the bounded log runner.')
}
if ($libraryPowerShellContent -notmatch 'HOST_LOG_MAX_SIZE_MB' -or $libraryPowerShellContent -notmatch 'HOST_LOG_MAX_FILES') {
    $failures.Add('Windows lifecycle must support configurable host log size and file-count limits.')
}
if ($libraryPowerShellContent -notmatch '\.Length\s+-gt\s+\$maxSizeBytes') {
    $failures.Add('Windows stale-log cleanup must remove oversized noncanonical logs.')
}

$libraryBash = Join-Path $repoRoot 'scripts/lib/lifecycle.sh'
$libraryBashContent = if (Test-Path -LiteralPath $libraryBash) { Get-Content -Raw $libraryBash } else { '' }
if ($libraryBashContent -notmatch 'run-with-log-limit\.mjs') {
    $failures.Add('Linux managed processes must use the bounded log runner.')
}
if ($libraryBashContent -notmatch 'HOST_LOG_MAX_SIZE_MB' -or $libraryBashContent -notmatch 'HOST_LOG_MAX_FILES') {
    $failures.Add('Linux lifecycle must support configurable host log size and file-count limits.')
}

$composePath = Join-Path $repoRoot 'deploy/docker-compose-env.yml'
$composeContent = if (Test-Path -LiteralPath $composePath) { Get-Content -Raw $composePath } else { '' }
if ($composeContent -notmatch '(?m)^x-logging:\s*&default-logging') {
    $failures.Add('Docker Compose must define a shared bounded logging policy.')
}
$servicesMatch = [regex]::Match($composeContent, '(?ms)^services:\s*\r?\n(?<body>.*?)^volumes:\s*$')
$serviceSection = $servicesMatch.Groups['body'].Value
$serviceCount = [regex]::Matches($serviceSection, '(?m)^  [a-zA-Z0-9_-]+:\s*$').Count
$loggingCount = [regex]::Matches($serviceSection, '(?m)^    logging:\s*\*default-logging\s*$').Count
if ($serviceCount -gt 0 -and $loggingCount -ne $serviceCount) {
    $failures.Add("Every Docker service must use bounded logging: services=$serviceCount loggingPolicies=$loggingCount")
}
if ($composeContent -notmatch 'DOCKER_LOG_MAX_SIZE' -or $composeContent -notmatch 'DOCKER_LOG_MAX_FILES') {
    $failures.Add('Docker logging limits must be configurable through deploy/.env.')
}

$dockerfileContent = Get-Content -Raw (Join-Path $repoRoot 'deploy/Dockerfile.python-ai-service')
if ($dockerfileContent -notmatch 'AI_ACCESS_LOG' -or $dockerfileContent -notmatch '--no-access-log') {
    $failures.Add('Python AI container must disable noisy access logs by default.')
}

$autoStarterContent = Get-Content -Raw (Join-Path $repoRoot 'src/main/java/com/xd/smartworksite/ai/infra/AiPythonServiceAutoStarter.java')
if ($autoStarterContent -notmatch 'ProcessBuilder\.Redirect\.INHERIT' -or $autoStarterContent -match 'Redirect\.appendTo') {
    $failures.Add('Auto-started Python output must flow through the bounded backend log stream.')
}

$requestFilterContent = Get-Content -Raw (Join-Path $repoRoot 'src/main/java/com/xd/smartworksite/common/config/RequestIdFilter.java')
if ($requestFilterContent -notmatch 'log\.debug\("http request') {
    $failures.Add('Per-request Java logging must be DEBUG instead of INFO by default.')
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host 'PASS: lifecycle script contracts are satisfied.' -ForegroundColor Green
