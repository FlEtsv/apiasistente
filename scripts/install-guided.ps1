#Requires -Version 5.1
<#
.SYNOPSIS
    Instalador guiado de ApiAsistente para Windows.
.DESCRIPTION
    Comprueba requisitos, gestiona modelos Ollama, provisiona MySQL y arranca la API.
    Versión 2.0 — con detección y descarga automática de modelos.
.PARAMETER NonInteractive
    No pide confirmaciones; usa los valores por defecto o los parámetros proporcionados.
.EXAMPLE
    .\install-guided.ps1
    .\install-guided.ps1 -NonInteractive -SkipOllamaCheck
#>
[CmdletBinding()]
param(
    [switch]$NonInteractive,
    [string]$OllamaBaseUrl       = "http://host.docker.internal:11434/api",
    [int]   $AppPort             = 8082,
    [int]   $MySqlPort           = 3306,
    [string]$DbName              = "apiasistente_db",
    [string]$DbUser              = "apiuser",
    [string]$DbPassword          = "apipassword",
    [string]$RootPassword        = "rootpassword",
    [string]$BootstrapAdminUsername = "admin",
    [string]$BootstrapAdminPassword = "",
    [string]$ExistingMySqlContainer = "",
    [switch]$SkipOllamaCheck,
    [switch]$SkipModelCheck,
    [switch]$RecreateAppContainer,
    [switch]$RecreateMySqlContainer
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# ── Modelos requeridos ─────────────────────────────────────────────────────────
$REQUIRED_MODELS = @(
    @{ Name = "nomic-embed-text:latest"; Purpose = "Embeddings RAG";    Priority = "crítico" },
    @{ Name = "qwen2.5:7b";             Purpose = "Modelo de chat rápido"; Priority = "recomendado" },
    @{ Name = "qwen3:14b";              Purpose = "Modelo de chat principal"; Priority = "opcional" }
)

# ── Paleta de colores ──────────────────────────────────────────────────────────
function Write-Step   ([string]$msg) { Write-Host "`n[$([char]0x25B6)] $msg" -ForegroundColor Cyan }
function Write-Ok     ([string]$msg) { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn   ([string]$msg) { Write-Host "    [!!] $msg" -ForegroundColor Yellow }
function Write-Fail   ([string]$msg) { Write-Host "    [XX] $msg" -ForegroundColor Red }
function Write-Info   ([string]$msg) { Write-Host "         $msg" -ForegroundColor DarkGray }
function Write-Header ([string]$msg) {
    $line = "=" * ($msg.Length + 4)
    Write-Host "`n$line" -ForegroundColor Cyan
    Write-Host "  $msg  " -ForegroundColor White
    Write-Host "$line`n" -ForegroundColor Cyan
}

function Read-WithDefault([string]$label, [string]$defaultValue) {
    $value = Read-Host "$label [$defaultValue]"
    return ([string]::IsNullOrWhiteSpace($value)) ? $defaultValue : $value.Trim()
}

function To-IntOrDefault([string]$raw, [int]$defaultValue) {
    $n = 0
    return ([int]::TryParse($raw, [ref]$n)) ? $n : $defaultValue
}

# ── Guardar configuración ──────────────────────────────────────────────────────
function Save-InstallEnv([hashtable]$cfg, [string]$targetPath) {
    @(
        "OLLAMA_BASE_URL=$($cfg.OllamaBaseUrl)",
        "MYSQL_PORT=$($cfg.MySqlPort)",
        "MYSQL_DB=$($cfg.DbName)",
        "MYSQL_USER=$($cfg.DbUser)",
        "MYSQL_PASSWORD=$($cfg.DbPassword)",
        "MYSQL_CONTAINER=$($cfg.MySqlContainer)",
        "BOOTSTRAP_ADMIN_USERNAME=$($cfg.BootstrapAdminUsername)",
        "BOOTSTRAP_ADMIN_PASSWORD=$($cfg.BootstrapAdminPassword)",
        "APP_PORT=$($cfg.AppPort)"
    ) | Set-Content -Path $targetPath -Encoding UTF8
}

# ── Detección de contenedores MySQL ───────────────────────────────────────────
function Get-MySqlCandidates {
    $rows = & docker ps -a --format "{{.Names}}|{{.Image}}|{{.Status}}" 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $rows) { return @() }
    $rows | Where-Object { $_ -match '(?i)(mysql|mariadb)' } | ForEach-Object {
        $parts = $_.Split("|")
        [pscustomobject]@{
            Name   = $parts[0].Trim()
            Image  = $parts[1].Trim()
            Status = if ($parts.Count -ge 3) { $parts[2].Trim() } else { "" }
        }
    }
}

function Select-MySqlContainer([switch]$IsNonInteractive, [string]$PreferredName) {
    if (-not [string]::IsNullOrWhiteSpace($PreferredName)) { return $PreferredName.Trim() }
    $candidates = @(Get-MySqlCandidates)
    if ($candidates.Count -eq 0) { return "" }
    $own = $candidates | Where-Object { $_.Name -eq "apiasistente_mysql" } | Select-Object -First 1
    if ($null -ne $own) { return $own.Name }
    if ($candidates.Count -eq 1) { return $candidates[0].Name }
    if ($IsNonInteractive) { throw "Varios contenedores MySQL. Usa -ExistingMySqlContainer." }
    Write-Host "Contenedores MySQL/MariaDB detectados:"
    for ($i = 0; $i -lt $candidates.Count; $i++) {
        $c = $candidates[$i]
        Write-Host "  [$i] $($c.Name) | $($c.Image) | $($c.Status)"
    }
    $answer = Read-Host "Índice para reutilizar (Enter = crear apiasistente_mysql)"
    if ([string]::IsNullOrWhiteSpace($answer)) { return "" }
    $idx = -1
    if (-not [int]::TryParse($answer, [ref]$idx) -or $idx -lt 0 -or $idx -ge $candidates.Count) {
        throw "Índice inválido: '$answer'"
    }
    return $candidates[$idx].Name
}

# ── Verificar Ollama y modelos ─────────────────────────────────────────────────
function Get-OllamaApiBase([string]$url) {
    # Convierte http://host:port/api → http://host:port
    return ($url -replace '/api/?$', '')
}

function Test-OllamaRunning([string]$ollamaUrl) {
    try {
        $base = Get-OllamaApiBase $ollamaUrl
        $resp = Invoke-WebRequest -Uri "$base/" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        return $resp.StatusCode -lt 400
    } catch { return $false }
}

function Get-OllamaModels([string]$ollamaUrl) {
    try {
        $base = Get-OllamaApiBase $ollamaUrl
        $resp = Invoke-RestMethod -Uri "$base/api/tags" -TimeoutSec 8 -ErrorAction Stop
        return $resp.models | ForEach-Object { $_.name }
    } catch { return @() }
}

function Invoke-ModelPull([string]$ollamaUrl, [string]$modelName) {
    $base = Get-OllamaApiBase $ollamaUrl
    Write-Info "Iniciando descarga de '$modelName' (puede tardar varios minutos)..."
    try {
        $body = "{`"name`":`"$modelName`"}"
        $resp = Invoke-RestMethod -Uri "$base/api/pull" -Method Post `
            -Body $body -ContentType "application/json" -TimeoutSec 600 -ErrorAction Stop
        return $true
    } catch {
        Write-Warn "No se pudo descargar '$modelName': $_"
        return $false
    }
}

function Check-OllamaModels([string]$ollamaUrl, [switch]$IsNonInteractive) {
    Write-Step "Verificando modelos Ollama..."

    $installedModels = @(Get-OllamaModels $ollamaUrl)
    $allOk = $true

    foreach ($model in $REQUIRED_MODELS) {
        $modelName = $model.Name
        $shortName = $modelName -replace ':.*$', ''

        # Verificar si el modelo o alguna variante está instalada
        $found = $installedModels | Where-Object {
            $_ -eq $modelName -or $_ -like "$shortName*"
        }

        if ($found) {
            Write-Ok "$modelName — $($model.Purpose)"
        } else {
            Write-Warn "$modelName ($($model.Priority)) — NO INSTALADO"
            Write-Info "  Propósito: $($model.Purpose)"

            if ($model.Priority -eq "crítico") {
                $allOk = $false
                $shouldPull = $true

                if (-not $IsNonInteractive) {
                    $answer = Read-Host "    ¿Descargar ahora? (s/N)"
                    $shouldPull = $answer -match '^[sS]'
                }

                if ($shouldPull) {
                    $ok = Invoke-ModelPull $ollamaUrl $modelName
                    if ($ok) {
                        Write-Ok "$modelName descargado correctamente"
                        $allOk = $true
                    } else {
                        Write-Fail "No se pudo descargar $modelName. La instalación puede fallar."
                    }
                } else {
                    Write-Warn "Omitido. El sistema RAG no funcionará sin el modelo de embeddings."
                }
            } elseif ($model.Priority -eq "recomendado") {
                if (-not $IsNonInteractive) {
                    $answer = Read-Host "    ¿Descargar ahora? (s/N)"
                    if ($answer -match '^[sS]') {
                        Invoke-ModelPull $ollamaUrl $modelName | Out-Null
                    }
                }
            } else {
                Write-Info "  Opcional. Puedes instalarlo después con: ollama pull $modelName"
            }
        }
    }

    return $allOk
}

# ═══════════════════════════════════════════════════════════════════════════════
# INICIO
# ═══════════════════════════════════════════════════════════════════════════════
Write-Header "ApiAsistente — Instalador Guiado v2.0"

if (-not $NonInteractive) {
    Write-Host "Pulsa Enter para aceptar cada valor por defecto." -ForegroundColor DarkGray
    $OllamaBaseUrl           = Read-WithDefault "Ollama base URL"               $OllamaBaseUrl
    $AppPort                 = To-IntOrDefault (Read-WithDefault "Puerto web de la app"      "$AppPort")    $AppPort
    $MySqlPort               = To-IntOrDefault (Read-WithDefault "Puerto host de MySQL"       "$MySqlPort")  $MySqlPort
    $DbName                  = Read-WithDefault "Nombre de base de datos"       $DbName
    $DbUser                  = Read-WithDefault "Usuario MySQL de la app"        $DbUser
    $DbPassword              = Read-WithDefault "Password MySQL de la app"       $DbPassword
    $RootPassword            = Read-WithDefault "Password root MySQL"            $RootPassword
    $BootstrapAdminUsername  = Read-WithDefault "Usuario admin bootstrap"        $BootstrapAdminUsername
    $BootstrapAdminPassword  = Read-WithDefault "Password admin (vacío = auto)"  $BootstrapAdminPassword
    Write-Host ""
}

$repoRoot     = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$checkScript  = Join-Path $PSScriptRoot "check-prerequisites.ps1"
$mysqlScript  = Join-Path $PSScriptRoot "run-mysql-container.ps1"
$appScript    = Join-Path $PSScriptRoot "run-app-container.ps1"

# ── 1) Preflight ──────────────────────────────────────────────────────────────
Write-Step "1/5 — Comprobando requisitos del sistema..."
& $checkScript -OllamaBaseUrl $OllamaBaseUrl -SkipOllamaCheck:$SkipOllamaCheck
if ($LASTEXITCODE -ne 0) { throw "Preflight fallido. Corrige los errores y reintenta." }
Write-Ok "Requisitos cumplidos"

# ── 2) Verificar Ollama y modelos ────────────────────────────────────────────
if (-not $SkipOllamaCheck -and -not $SkipModelCheck) {
    Write-Step "2/5 — Verificando Ollama y modelos de IA..."
    if (Test-OllamaRunning $OllamaBaseUrl) {
        Write-Ok "Ollama accesible en $OllamaBaseUrl"
        $modelsOk = Check-OllamaModels -ollamaUrl $OllamaBaseUrl -IsNonInteractive:$NonInteractive
        if (-not $modelsOk) {
            Write-Warn "Algunos modelos críticos no están disponibles."
            if (-not $NonInteractive) {
                $cont = Read-Host "¿Continuar igualmente? (s/N)"
                if ($cont -notmatch '^[sS]') { exit 1 }
            }
        }
    } else {
        Write-Warn "Ollama no está accesible en $OllamaBaseUrl"
        Write-Info "El chat y RAG funcionarán solo cuando Ollama esté disponible."
        Write-Info "Docs: https://ollama.com/download"
    }
} else {
    Write-Info "2/5 — Verificación de modelos omitida."
}

# ── 3) MySQL ──────────────────────────────────────────────────────────────────
Write-Step "3/5 — Provisionando MySQL..."
$resolvedMySqlContainer = Select-MySqlContainer -IsNonInteractive:$NonInteractive -PreferredName $ExistingMySqlContainer
$reuseExistingMySql     = -not [string]::IsNullOrWhiteSpace($resolvedMySqlContainer)

if ($reuseExistingMySql) {
    Write-Info "Reutilizando contenedor existente: $resolvedMySqlContainer"
    & $mysqlScript -UseExistingContainer $resolvedMySqlContainer `
        -DbName $DbName -DbUser $DbUser -DbPassword $DbPassword `
        -RootPassword $RootPassword -SkipDbInit
} else {
    $resolvedMySqlContainer = "apiasistente_mysql"
    Write-Info "Creando contenedor: $resolvedMySqlContainer"
    & $mysqlScript -ContainerName $resolvedMySqlContainer -HostPort $MySqlPort `
        -DbName $DbName -DbUser $DbUser -DbPassword $DbPassword `
        -RootPassword $RootPassword -Recreate:$RecreateMySqlContainer
}
if ($LASTEXITCODE -ne 0) { throw "No se pudo provisionar MySQL." }
Write-Ok "MySQL listo"

# ── 4) Guardar config ────────────────────────────────────────────────────────
Write-Step "4/5 — Guardando configuración de instalación..."
$cfg = @{
    OllamaBaseUrl          = $OllamaBaseUrl
    AppPort                = $AppPort
    MySqlPort              = $MySqlPort
    DbName                 = $DbName
    DbUser                 = $DbUser
    DbPassword             = $DbPassword
    MySqlContainer         = $resolvedMySqlContainer
    BootstrapAdminUsername = $BootstrapAdminUsername
    BootstrapAdminPassword = $BootstrapAdminPassword
}
$envPath = Join-Path $repoRoot ".install.env"
Save-InstallEnv -cfg $cfg -targetPath $envPath
Write-Ok "Configuración guardada en $envPath"

# ── 5) API ───────────────────────────────────────────────────────────────────
Write-Step "5/5 — Provisionando API..."
& $appScript `
    -HostPort $AppPort `
    -OllamaBaseUrl $OllamaBaseUrl `
    -MySqlHost $resolvedMySqlContainer `
    -MySqlPort 3306 `
    -DbName $DbName `
    -DbUser $DbUser `
    -DbPassword $DbPassword `
    -BootstrapAdminUsername $BootstrapAdminUsername `
    -BootstrapAdminPassword $BootstrapAdminPassword `
    -Recreate:($RecreateAppContainer -or -not $NonInteractive)
if ($LASTEXITCODE -ne 0) { throw "No se pudo provisionar la API." }
Write-Ok "API iniciada"

# ── Resumen final ─────────────────────────────────────────────────────────────
Write-Header "Instalación Completada"
Write-Host "  URL de acceso : http://localhost:$AppPort/login" -ForegroundColor White
Write-Host "  Admin         : $BootstrapAdminUsername" -ForegroundColor White
if ([string]::IsNullOrWhiteSpace($BootstrapAdminPassword)) {
    Write-Host "  Password      : ver data/bootstrap-admin.txt" -ForegroundColor Yellow
} else {
    Write-Host "  Password      : (el que configuraste)" -ForegroundColor White
}
Write-Host ""
Write-Host "  Próximos pasos:" -ForegroundColor DarkGray
Write-Host "    1. Accede a http://localhost:$AppPort/login" -ForegroundColor DarkGray
Write-Host "    2. Completa la configuración en /setup" -ForegroundColor DarkGray
Write-Host "    3. Sube documentos en /rag-admin" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  Modelos Ollama disponibles para instalar después:" -ForegroundColor DarkGray
foreach ($m in $REQUIRED_MODELS) {
    Write-Host "    ollama pull $($m.Name)   # $($m.Purpose)" -ForegroundColor DarkGray
}
Write-Host ""