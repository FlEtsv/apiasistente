[CmdletBinding()]
param(
    [string]$ContainerName = "apiasistente",
    [string]$ImageName = "apiasistente:local",
    [string]$NetworkName = "apiasistente_net",
    [int]$HostPort = 8082,
    [string]$OllamaBaseUrl = "http://host.docker.internal:11434/api",
    [string]$MySqlHost = "apiasistente_mysql",
    [int]$MySqlPort = 3306,
    [string]$DbName = "apiasistente_db",
    [string]$DbUser = "apiuser",
    [string]$DbPassword = "apipassword",
    [string]$BootstrapAdminEnabled = "true",
    [string]$BootstrapAdminUsername = "admin",
    [string]$BootstrapAdminPassword = "",
    [string]$BootstrapAdminOutputFile = "data/bootstrap-admin.txt",
    [string]$BundledJarRelativePath = "app/apiasistente.jar",
    [int]$WaitTimeoutSec = 240,
    [switch]$SkipBuild,
    [switch]$ForceBuildImage,
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"

function Invoke-Docker([string[]]$Args) {
    $output = & docker @Args 2>&1
    if ($LASTEXITCODE -ne 0) {
        $text = ($output | Out-String).Trim()
        throw "docker $($Args -join ' ') fallo: $text"
    }
    return ($output | Out-String).Trim()
}

function Container-Exists([string]$Name) {
    & docker container inspect $Name *> $null
    return ($LASTEXITCODE -eq 0)
}

function Ensure-Network([string]$Name) {
    $existing = & docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $Name }
    if (-not $existing) {
        Write-Host "Creando red Docker '$Name'..."
        Invoke-Docker @("network", "create", $Name) | Out-Null
    } else {
        Write-Host "Red Docker '$Name' ya existe."
    }
}

function Wait-ApiHealth([int]$Port, [int]$TimeoutSec) {
    $url = "http://localhost:$Port/actuator/health"
    $deadline = (Get-Date).AddSeconds([Math]::Max(30, $TimeoutSec))
    while ((Get-Date) -lt $deadline) {
        try {
            $res = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 4
            if ($null -ne $res -and $res.status -eq "UP") {
                return $true
            }
        } catch {
            # sigue esperando
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$dataDir = Join-Path $repoRoot "data"
New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
$dataDirResolved = (Resolve-Path $dataDir).Path
$bundledJarPath = Join-Path $repoRoot ($BundledJarRelativePath -replace "/", [System.IO.Path]::DirectorySeparatorChar)
$useBundledJar = (Test-Path $bundledJarPath) -and (-not $ForceBuildImage)
if ($useBundledJar) {
    $bundledJarPath = (Resolve-Path $bundledJarPath).Path
}

Write-Host "== Provisioning API container =="
Invoke-Docker @("info") | Out-Null
Ensure-Network -Name $NetworkName

if ($useBundledJar) {
    Write-Host "Modo bundle detectado: se usara JAR local en contenedor Java."
} else {
    if (-not $SkipBuild) {
        Write-Host "Construyendo imagen local '$ImageName'..."
        Invoke-Docker @("build", "-t", $ImageName, $repoRoot) | Out-Null
    } else {
        Write-Host "Build omitido por parametro."
    }
}

if (Container-Exists -Name $ContainerName) {
    if ($Recreate) {
        Write-Host "Eliminando contenedor existente '$ContainerName'..."
        Invoke-Docker @("rm", "-f", $ContainerName) | Out-Null
    } else {
        $state = Invoke-Docker @("inspect", "-f", "{{.State.Status}}", $ContainerName)
        if ($state -ne "running") {
            Write-Host "Iniciando contenedor existente '$ContainerName'..."
            Invoke-Docker @("start", $ContainerName) | Out-Null
        } else {
            Write-Host "Contenedor '$ContainerName' ya estaba en ejecucion."
        }
    }
}

if (-not (Container-Exists -Name $ContainerName)) {
    Write-Host "Creando contenedor API '$ContainerName'..."
    if ($useBundledJar) {
        Invoke-Docker @(
            "run", "-d",
            "--name", $ContainerName,
            "--network", $NetworkName,
            "-p", "$HostPort`:8082",
            "--add-host", "host.docker.internal:host-gateway",
            "-e", "SERVER_PORT=8082",
            "-e", "MYSQL_HOST=$MySqlHost",
            "-e", "MYSQL_PORT=$MySqlPort",
            "-e", "MYSQL_DB=$DbName",
            "-e", "MYSQL_USER=$DbUser",
            "-e", "MYSQL_PASSWORD=$DbPassword",
            "-e", "OLLAMA_BASE_URL=$OllamaBaseUrl",
            "-e", "BOOTSTRAP_ADMIN_ENABLED=$BootstrapAdminEnabled",
            "-e", "BOOTSTRAP_ADMIN_USERNAME=$BootstrapAdminUsername",
            "-e", "BOOTSTRAP_ADMIN_PASSWORD=$BootstrapAdminPassword",
            "-e", "BOOTSTRAP_ADMIN_OUTPUT_FILE=$BootstrapAdminOutputFile",
            "-v", "$dataDirResolved`:/app/data",
            "-v", "$bundledJarPath`:/opt/apiasistente/apiasistente.jar:ro",
            "eclipse-temurin:21-jre",
            "java", "-jar", "/opt/apiasistente/apiasistente.jar"
        ) | Out-Null
    } else {
        Invoke-Docker @(
            "run", "-d",
            "--name", $ContainerName,
            "--network", $NetworkName,
            "-p", "$HostPort`:8082",
            "--add-host", "host.docker.internal:host-gateway",
            "-e", "SERVER_PORT=8082",
            "-e", "MYSQL_HOST=$MySqlHost",
            "-e", "MYSQL_PORT=$MySqlPort",
            "-e", "MYSQL_DB=$DbName",
            "-e", "MYSQL_USER=$DbUser",
            "-e", "MYSQL_PASSWORD=$DbPassword",
            "-e", "OLLAMA_BASE_URL=$OllamaBaseUrl",
            "-e", "BOOTSTRAP_ADMIN_ENABLED=$BootstrapAdminEnabled",
            "-e", "BOOTSTRAP_ADMIN_USERNAME=$BootstrapAdminUsername",
            "-e", "BOOTSTRAP_ADMIN_PASSWORD=$BootstrapAdminPassword",
            "-e", "BOOTSTRAP_ADMIN_OUTPUT_FILE=$BootstrapAdminOutputFile",
            "-v", "$dataDirResolved`:/app/data",
            $ImageName
        ) | Out-Null
    }
}

Write-Host "Esperando a que la API responda..."
$ready = Wait-ApiHealth -Port $HostPort -TimeoutSec $WaitTimeoutSec
if (-not $ready) {
    throw "La API no quedo lista dentro de $WaitTimeoutSec segundos."
}

$credentialPath = if ([System.IO.Path]::IsPathRooted($BootstrapAdminOutputFile)) {
    $BootstrapAdminOutputFile
} else {
    Join-Path $repoRoot ($BootstrapAdminOutputFile -replace "/", [System.IO.Path]::DirectorySeparatorChar)
}

Write-Host "API lista en http://localhost:$HostPort" -ForegroundColor Green
Write-Host "Login: http://localhost:$HostPort/login" -ForegroundColor Green
Write-Host "Setup: http://localhost:$HostPort/setup" -ForegroundColor Green
Write-Host "Credenciales bootstrap: $credentialPath" -ForegroundColor Green
