[CmdletBinding()]
param(
    [string]$OllamaBaseUrl = "http://localhost:11434/api",
    [switch]$SkipOllamaCheck
)

$ErrorActionPreference = "Stop"

function Write-Ok([string]$message) {
    Write-Host "[OK] $message" -ForegroundColor Green
}

function Write-Warn([string]$message) {
    Write-Host "[WARN] $message" -ForegroundColor Yellow
}

function Write-Fail([string]$message) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
}

function Normalize-OllamaApiBase([string]$rawUrl) {
    $value = if ([string]::IsNullOrWhiteSpace($rawUrl)) { "http://localhost:11434/api" } else { $rawUrl.Trim() }
    $value = $value.TrimEnd("/")
    if (-not $value.ToLowerInvariant().EndsWith("/api")) {
        $value = "$value/api"
    }
    return $value
}

$hasErrors = $false

Write-Host "== Preflight: Docker + Ollama ==" -ForegroundColor Cyan

$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $dockerCmd) {
    Write-Fail "Docker CLI no esta instalado o no esta en PATH."
    $hasErrors = $true
} else {
    Write-Ok "Docker CLI detectado."
}

if (-not $hasErrors) {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "Docker daemon no responde. Abre Docker Desktop y prueba de nuevo."
        $hasErrors = $true
    } else {
        Write-Ok "Docker daemon activo."
    }
}

if (-not $hasErrors) {
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "Falta el plugin docker compose."
        $hasErrors = $true
    } else {
        Write-Ok "docker compose disponible."
    }
}

if (-not $SkipOllamaCheck) {
    $ollamaApiBase = Normalize-OllamaApiBase $OllamaBaseUrl
    $ollamaTagsUrl = "$ollamaApiBase/tags"
    try {
        $null = Invoke-RestMethod -Uri $ollamaTagsUrl -Method Get -TimeoutSec 5
        Write-Ok "Ollama API accesible en $ollamaApiBase"
    } catch {
        $ollamaCmd = Get-Command ollama -ErrorAction SilentlyContinue
        if ($null -eq $ollamaCmd) {
            Write-Fail "No se pudo conectar a Ollama ($ollamaApiBase) y el comando 'ollama' no esta instalado."
            Write-Warn "Instala Ollama o apunta OLLAMA_BASE_URL a un servidor Ollama operativo."
        } else {
            Write-Fail "Comando 'ollama' detectado pero la API no responde en $ollamaApiBase."
            Write-Warn "Ejecuta 'ollama serve' o ajusta OLLAMA_BASE_URL."
        }
        $hasErrors = $true
    }
} else {
    Write-Warn "Chequeo Ollama omitido por parametro."
}

if ($hasErrors) {
    Write-Host "Preflight finalizado con errores." -ForegroundColor Red
    exit 1
}

Write-Host "Preflight OK. Puedes continuar con la instalacion." -ForegroundColor Green
exit 0
