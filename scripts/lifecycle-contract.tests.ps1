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
    if ($startPowerShellContent -notmatch "-Arguments\s+@\('up',\s*'-d'\)") {
        $failures.Add('scripts/start-all.ps1 must pass Docker Compose detached mode without PowerShell treating -d as a script parameter.')
    }
}

$statusPowerShell = Join-Path $repoRoot 'scripts/status.ps1'
if ((Test-Path -LiteralPath $statusPowerShell) -and (Get-Content -Raw $statusPowerShell) -notmatch 'Test-HttpEndpoint') {
    $failures.Add('scripts/status.ps1 must check frontend HTTP readiness.')
}

$startBash = Join-Path $repoRoot 'scripts/start-all.sh'
if ((Test-Path -LiteralPath $startBash) -and (Get-Content -Raw $startBash) -notmatch '--check') {
    $failures.Add('scripts/start-all.sh must expose the --check option.')
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

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host 'PASS: lifecycle script contracts are satisfied.' -ForegroundColor Green
