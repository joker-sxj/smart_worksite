[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\lifecycle.ps1')

$projectRoot = Get-ProjectRoot
$runDirectory = Join-Path $projectRoot 'logs\run'

try {
    Stop-ManagedProcess -Name 'Vue frontend' -ProjectRoot $projectRoot -Marker 'npm run dev' -PidFile (Join-Path $runDirectory 'frontend.pid')
    Stop-ManagedProcess -Name 'Java backend' -ProjectRoot $projectRoot -Marker 'spring-boot:run' -PidFile (Join-Path $runDirectory 'backend.pid')
    Write-Host 'Stopping Docker Compose services (volumes are preserved)...' -ForegroundColor Cyan
    Invoke-ProjectCompose -ProjectRoot $projectRoot down
    Write-Host 'Smart Worksite is stopped. Persistent Docker volumes were preserved.' -ForegroundColor Green
} catch { Write-Error $_.Exception.Message; exit 1 }
