<#
.SYNOPSIS
Activa servicios del stack de observabilidad via Docker Compose.

.DESCRIPTION
Levanta (o reintenta levantar) API, Prometheus y Grafana.
No modifica configuracion funcional de la aplicacion.
#>
param(
    [string]$ProjectDir = ".",
    [string]$ComposeFile = "docker-compose.yml",
    [string]$Services = "api,prometheus,grafana"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedProject = (Resolve-Path -Path $ProjectDir).Path
Set-Location $resolvedProject

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker CLI no disponible en este host."
}

$composeBase = @()
$composePluginOk = $false
try {
    & docker compose version | Out-Null
    $composePluginOk = $true
} catch {
    $composePluginOk = $false
}

if ($composePluginOk) {
    $composeBase = @("docker", "compose")
} else {
    if (-not (Get-Command docker-compose -ErrorAction SilentlyContinue)) {
        throw "Docker Compose no disponible."
    }
    try {
        & docker-compose version | Out-Null
        $composeBase = @("docker-compose")
    } catch {
        throw "Docker Compose no disponible."
    }
}

if (-not (Test-Path -Path $ComposeFile)) {
    throw "No existe el compose file: $ComposeFile"
}

$serviceList = @($Services -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
if ($serviceList.Count -eq 0) {
    $serviceList = @("api", "prometheus", "grafana")
}

$invokeArgs = @()
if ($composeBase.Count -gt 1) {
    $invokeArgs += $composeBase[1]
}
$invokeArgs += @("-f", $ComposeFile, "up", "-d")
$includesApi = $serviceList | Where-Object { $_.ToLowerInvariant() -eq "api" }
if (-not $includesApi) {
    $invokeArgs += "--no-deps"
}
$invokeArgs += $serviceList

& $composeBase[0] @invokeArgs

Write-Output "Estado actual de contenedores relevantes:"
& docker ps --format "{{.Names}}`t{{.Status}}" |
    Select-String "apiasistente|prometheus|grafana|mysql" |
    ForEach-Object { $_.Line }

Write-Output "OK: stack de observabilidad activado."
