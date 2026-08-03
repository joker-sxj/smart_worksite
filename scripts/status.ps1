[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\lifecycle.ps1')

$projectRoot = Get-ProjectRoot
$envFile = Join-Path $projectRoot 'deploy\.env'
$runDirectory = Join-Path $projectRoot 'logs\run'
$healthy = $true

try {
    Import-DotEnv -Path $envFile
    Write-Host 'Docker Compose services:' -ForegroundColor Cyan
    Invoke-ProjectCompose -ProjectRoot $projectRoot ps

    foreach ($managed in @(
        @{ Name = 'Java backend'; File = 'backend.pid'; Marker = 'spring-boot:run' },
        @{ Name = 'Vue frontend'; File = 'frontend.pid'; Marker = 'npm run dev' }
    )) {
        $process = Get-ManagedProcess -PidFile (Join-Path $runDirectory $managed.File) -ProjectRoot $projectRoot -Marker $managed.Marker
        if ($process) { Write-Host "$($managed.Name): RUNNING (PID $($process.Id))" -ForegroundColor Green }
        else { Write-Host "$($managed.Name): no managed PID" -ForegroundColor Yellow }
    }

    $ports = [ordered]@{
        Backend = (Get-ConfiguredPort -Name 'SERVER_PORT' -Default 8080)
        PythonAI = (Get-ConfiguredPort -Name 'AI_SERVICE_PORT' -Default 8015)
        MySQL = (Get-ConfiguredPort -Name 'MYSQL_PORT' -Default 3306)
        Redis = (Get-ConfiguredPort -Name 'REDIS_PORT' -Default 6379)
        MinIO = (Get-ConfiguredPort -Name 'MINIO_API_PORT' -Default 9000)
        MinIOConsole = (Get-ConfiguredPort -Name 'MINIO_CONSOLE_PORT' -Default 9001)
    }
    $frontendHealthy = Test-HttpEndpoint -Uri 'http://localhost:5173/'
    Write-Host "Frontend HTTP: $(if ($frontendHealthy) { 'UP' } else { 'DOWN' })" -ForegroundColor $(if ($frontendHealthy) { 'Green' } else { 'Red' })
    $healthy = $healthy -and $frontendHealthy
    foreach ($entry in $ports.GetEnumerator()) {
        if (Test-TcpPort -Port $entry.Value) { Write-Host "$($entry.Key): LISTENING on $($entry.Value)" -ForegroundColor Green }
        else { Write-Host "$($entry.Key): DOWN on $($entry.Value)" -ForegroundColor Red; $healthy = $false }
    }

    $backendHealthy = Test-HttpHealth -Uri "http://127.0.0.1:$($ports.Backend)/actuator/health"
    $aiHealthy = Test-HttpHealth -Uri "http://127.0.0.1:$($ports.PythonAI)/v1/health"
    Write-Host "Backend health: $(if ($backendHealthy) { 'UP' } else { 'DOWN' })" -ForegroundColor $(if ($backendHealthy) { 'Green' } else { 'Red' })
    Write-Host "Python AI health: $(if ($aiHealthy) { 'UP' } else { 'DOWN' })" -ForegroundColor $(if ($aiHealthy) { 'Green' } else { 'Red' })
    $healthy = $healthy -and $backendHealthy -and $aiHealthy
} catch { Write-Error $_.Exception.Message; exit 1 }

if (-not $healthy) { exit 1 }
