[CmdletBinding()]
param([switch]$Check)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\lifecycle.ps1')

$projectRoot = Get-ProjectRoot
$envFile = Join-Path $projectRoot 'deploy\.env'
$envExample = Join-Path $projectRoot 'deploy\.env.example'
$logDirectory = Join-Path $projectRoot 'logs'
$runDirectory = Join-Path $logDirectory 'run'

try {
    foreach ($command in @('docker', 'java', 'mvn', 'node', 'npm')) { Assert-RequiredCommand -Name $command }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is required. Install or update Docker Desktop/Docker Engine.' }

    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        if (Test-Path -LiteralPath $envExample -PathType Leaf) { Copy-Item -LiteralPath $envExample -Destination $envFile }
        throw "Created $envFile. Configure its local model endpoints, then run this script again."
    }

    Import-DotEnv -Path $envFile
    Assert-MinimumFreeDisk -Path $projectRoot
    $deploymentMode = if ([string]::IsNullOrWhiteSpace($env:AI_DEPLOYMENT_MODE)) { 'CLOUD_ALLOWED' } else { $env:AI_DEPLOYMENT_MODE.ToUpperInvariant() }
    if ($deploymentMode -eq 'CLOUD_ALLOWED' -and [string]::IsNullOrWhiteSpace($env:QWEN_API_KEY)) {
        throw 'QWEN_API_KEY is required when AI_DEPLOYMENT_MODE=CLOUD_ALLOWED.'
    }

    $javaVersionOutput = (& cmd.exe /d /c 'java -version 2>&1') -join "`n"
    if ($javaVersionOutput -notmatch 'version\s+"(?<major>\d+)') { throw "Unable to determine Java version from: $javaVersionOutput" }
    if ([int]$Matches.major -lt 17) { throw "Java 17 or newer is required; detected Java $($Matches.major)." }

    Write-Host 'Prerequisite and configuration checks passed.' -ForegroundColor Green
    if ($Check) {
        Write-Host 'Check mode completed; no services were started.' -ForegroundColor Cyan
        exit 0
    }

    New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
    Remove-StaleProjectLogs -LogDirectory $logDirectory
    Write-Host 'Starting Docker Compose services...' -ForegroundColor Cyan
    Invoke-ProjectCompose -ProjectRoot $projectRoot -Arguments @('up', '-d', '--build')

    $mysqlPort = Get-ConfiguredPort -Name 'MYSQL_PORT' -Default 3306
    $redisPort = Get-ConfiguredPort -Name 'REDIS_PORT' -Default 6379
    $minioPort = Get-ConfiguredPort -Name 'MINIO_API_PORT' -Default 9000
    $aiPort = Get-ConfiguredPort -Name 'AI_SERVICE_PORT' -Default 8015
    $serverPort = Get-ConfiguredPort -Name 'SERVER_PORT' -Default 8080

    Wait-TcpPort -Name 'MySQL' -Port $mysqlPort | Out-Null
    Wait-TcpPort -Name 'Redis' -Port $redisPort | Out-Null
    Wait-TcpPort -Name 'MinIO' -Port $minioPort | Out-Null
    Wait-HttpHealth -Name 'Python AI service' -Uri "http://127.0.0.1:$aiPort/v1/health" | Out-Null

    $backendHealthUri = "http://127.0.0.1:$serverPort/actuator/health"
    Assert-ServicePortAvailable -Name 'Java backend' -Port $serverPort -Uri $backendHealthUri
    if (-not (Test-HttpHealth -Uri $backendHealthUri)) {
        Start-ManagedProcess -Name 'Java backend' -ProjectRoot $projectRoot -WorkingDirectory $projectRoot `
            -Command '& mvn spring-boot:run' -Marker 'spring-boot:run' `
            -PidFile (Join-Path $runDirectory 'backend.pid') `
            -StdOutFile (Join-Path $logDirectory 'backend.out.log') `
            -StdErrFile (Join-Path $logDirectory 'backend.err.log') | Out-Null
    } else { Write-Host 'Java backend is already healthy.' -ForegroundColor Yellow }
    try {
        Wait-HttpHealth -Name 'Java backend' -Uri $backendHealthUri -TimeoutSeconds 120 | Out-Null
    } catch { throw "$($_.Exception.Message) See logs/backend.out.log and logs/backend.err.log." }

    $frontendUri = 'http://localhost:5173/'
    Assert-ServicePortAvailable -Name 'Vue frontend' -Port 5173 -Uri $frontendUri -Check Http -HostName 'localhost'
    if (-not (Test-HttpEndpoint -Uri $frontendUri)) {
        $frontendDirectory = Join-Path $projectRoot 'frontend'
        if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules'))) {
            Write-Host 'Installing frontend dependencies...' -ForegroundColor Cyan
            Push-Location $frontendDirectory
            try {
                & npm install
                if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
            } finally { Pop-Location }
        }
        Start-ManagedProcess -Name 'Vue frontend' -ProjectRoot $projectRoot -WorkingDirectory $frontendDirectory `
            -Command '& npm run dev' -Marker 'npm run dev' `
            -PidFile (Join-Path $runDirectory 'frontend.pid') `
            -StdOutFile (Join-Path $logDirectory 'frontend.out.log') `
            -StdErrFile (Join-Path $logDirectory 'frontend.err.log') | Out-Null
    } else { Write-Host 'Vue frontend is already serving HTTP on port 5173.' -ForegroundColor Yellow }
    try {
        Wait-HttpEndpoint -Name 'Vue frontend' -Uri $frontendUri -TimeoutSeconds 90 | Out-Null
    } catch { throw "$($_.Exception.Message) See logs/frontend.out.log and logs/frontend.err.log." }

    Write-Host ''
    Write-Host 'Smart Worksite is ready.' -ForegroundColor Green
    Write-Host 'Frontend: http://localhost:5173'
    Write-Host "Backend health: http://127.0.0.1:$serverPort/actuator/health"
    Write-Host "Python AI health: http://127.0.0.1:$aiPort/v1/health"
    Write-Host "Logs: $logDirectory"
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
