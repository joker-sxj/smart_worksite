Set-StrictMode -Version Latest

function Get-ProjectRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Get-ComposeArguments {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)
    return @('compose', '-f', (Join-Path $ProjectRoot 'deploy\docker-compose-env.yml'), '--env-file', (Join-Path $ProjectRoot 'deploy\.env'))
}

function Import-DotEnv {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Configuration file not found: $Path" }
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#') -or -not $line.Contains('=')) { continue }
        if ($line.StartsWith('export ')) { $line = $line.Substring(7).Trim() }
        $name, $value = $line -split '=', 2
        $name = $name.Trim(); $value = $value.Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { continue }
        if ($value.Length -ge 2) {
            $first = $value[0]; $last = $value[$value.Length - 1]
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Assert-RequiredCommand {
    param([Parameter(Mandatory = $true)][string]$Name, [string]$InstallHint = '')
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        $message = "Required command is not available: $Name"
        if ($InstallHint) { $message += ". $InstallHint" }
        throw $message
    }
}

function Test-TcpPort {
    param([string]$HostName = '127.0.0.1', [Parameter(Mandatory = $true)][int]$Port, [int]$TimeoutMilliseconds = 500)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connectTask = $client.ConnectAsync($HostName, $Port)
        return $connectTask.Wait($TimeoutMilliseconds) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Wait-TcpPort {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][int]$Port, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-TcpPort -Port $Port) { return $true }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not start on port $Port within $TimeoutSeconds seconds."
}

function Test-HttpEndpoint {
    param([Parameter(Mandatory = $true)][string]$Uri, [int]$TimeoutSeconds = 5)
    try {
        $response = Invoke-WebRequest -Uri $Uri -Method Get -TimeoutSec $TimeoutSeconds -UseBasicParsing
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 400
    } catch { return $false }
}

function Get-ListeningProcessIds {
    param([Parameter(Mandatory = $true)][int]$Port)
    try {
        return @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop |
            Select-Object -ExpandProperty OwningProcess -Unique | Sort-Object)
    } catch { return @() }
}

function Assert-ServicePortAvailable {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Uri,
        [ValidateSet('Health', 'Http')][string]$Check = 'Health',
        [string]$HostName = '127.0.0.1'
    )
    if (-not (Test-TcpPort -HostName $HostName -Port $Port)) { return }
    $ready = if ($Check -eq 'Http') { Test-HttpEndpoint -Uri $Uri } else { Test-HttpHealth -Uri $Uri }
    if ($ready) { return }
    $processIds = @(Get-ListeningProcessIds -Port $Port)
    $owner = if ($processIds.Count -gt 0) { " PID(s): $($processIds -join ', ')" } else { '' }
    throw "$Name cannot start because port $Port is occupied$owner, but $Uri is not responding as expected. Stop the occupying process or change the configured port."
}

function Test-HttpHealth {
    param([Parameter(Mandatory = $true)][string]$Uri, [int]$TimeoutSeconds = 5)
    try {
        $response = Invoke-RestMethod -Uri $Uri -Method Get -TimeoutSec $TimeoutSeconds
        $statusProperty = $response.PSObject.Properties['status']
        if ($statusProperty -and $statusProperty.Value -eq 'UP') { return $true }
        $dataProperty = $response.PSObject.Properties['data']
        if ($dataProperty -and $dataProperty.Value) {
            $nestedStatus = $dataProperty.Value.PSObject.Properties['status']
            if ($nestedStatus -and $nestedStatus.Value -eq 'UP') { return $true }
        }
        return $false
    } catch { return $false }
}

function Wait-HttpEndpoint {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][string]$Uri, [int]$TimeoutSeconds = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-HttpEndpoint -Uri $Uri) { return $true }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Name HTTP check failed at $Uri after $TimeoutSeconds seconds."
}
function Wait-HttpHealth {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][string]$Uri, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-HttpHealth -Uri $Uri) { return $true }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Name health check failed at $Uri after $TimeoutSeconds seconds."
}

function Get-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$PidFile,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$Marker
    )
    if (-not (Test-Path -LiteralPath $PidFile -PathType Leaf)) { return $null }
    $rawPid = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    $processId = 0
    if (-not [int]::TryParse($rawPid, [ref]$processId)) { return $null }
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    $cimProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if (-not $cimProcess -or -not $cimProcess.CommandLine) { return $null }
    $commandLine = $cimProcess.CommandLine.ToLowerInvariant()
    if (-not $commandLine.Contains($ProjectRoot.ToLowerInvariant()) -or -not $commandLine.Contains($Marker.ToLowerInvariant())) { return $null }
    return $process
}

function Start-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string]$Marker,
        [Parameter(Mandatory = $true)][string]$PidFile,
        [Parameter(Mandatory = $true)][string]$StdOutFile,
        [Parameter(Mandatory = $true)][string]$StdErrFile
    )
    $existing = Get-ManagedProcess -PidFile $PidFile -ProjectRoot $ProjectRoot -Marker $Marker
    if ($existing) {
        Write-Host "$Name is already running (PID $($existing.Id))." -ForegroundColor Yellow
        return $existing
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    $escapedDirectory = $WorkingDirectory.Replace("'", "''")
    $script = "Set-Location -LiteralPath '$escapedDirectory'; $Command"
    $process = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $script) `
        -RedirectStandardOutput $StdOutFile -RedirectStandardError $StdErrFile -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $PidFile -Value $process.Id -Encoding ascii
    Write-Host "Started $Name (PID $($process.Id))." -ForegroundColor Green
    return $process
}

function Stop-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$Marker,
        [Parameter(Mandatory = $true)][string]$PidFile
    )
    $process = Get-ManagedProcess -PidFile $PidFile -ProjectRoot $ProjectRoot -Marker $Marker
    if (-not $process) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        Write-Host "$Name is already stopped or its PID file is stale." -ForegroundColor Yellow
        return
    }
    & taskkill.exe /PID $process.Id /T /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to stop $Name process tree (PID $($process.Id))." }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host "Stopped $Name." -ForegroundColor Green
}

function Invoke-ProjectCompose {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )
    $composeArguments = Get-ComposeArguments -ProjectRoot $ProjectRoot
    & docker @composeArguments @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose command failed: $($Arguments -join ' ')" }
}

function Get-ConfiguredPort {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][int]$Default)
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    $port = 0
    if ($value -and [int]::TryParse($value, [ref]$port)) { return $port }
    return $Default
}
