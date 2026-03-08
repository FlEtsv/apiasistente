[CmdletBinding()]
param(
    [string]$OutputDir = "dist",
    [string]$BundleName = "apiasistente-installer",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"

function Resolve-BootJar([string]$repoRoot) {
    $libsDir = Join-Path $repoRoot "build/libs"
    if (-not (Test-Path $libsDir)) {
        throw "No existe build/libs. Ejecuta bootJar antes."
    }
    $candidates = Get-ChildItem -Path $libsDir -Filter "*.jar" -File |
        Where-Object { $_.Name -notmatch "-plain\.jar$" } |
        Sort-Object Length -Descending

    if ($candidates.Count -eq 0) {
        throw "No se encontro jar ejecutable en build/libs."
    }
    return $candidates[0].FullName
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repoRoot
try {
    if (-not $SkipTests) {
        Write-Host "Ejecutando tests..."
        & ./gradlew.bat test
        if ($LASTEXITCODE -ne 0) { throw "Fallo ./gradlew test" }
    }

    Write-Host "Generando bootJar..."
    & ./gradlew.bat bootJar
    if ($LASTEXITCODE -ne 0) { throw "Fallo ./gradlew bootJar" }

    $jarPath = Resolve-BootJar -repoRoot $repoRoot
    $outRoot = Join-Path $repoRoot $OutputDir
    $bundleDir = Join-Path $outRoot $BundleName
    $appDir = Join-Path $bundleDir "app"
    $scriptsDir = Join-Path $bundleDir "scripts"
    $docsDir = Join-Path $bundleDir "docs"

    if (Test-Path $bundleDir) {
        Remove-Item -Path $bundleDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $appDir -Force | Out-Null
    New-Item -ItemType Directory -Path $scriptsDir -Force | Out-Null
    New-Item -ItemType Directory -Path $docsDir -Force | Out-Null

    Copy-Item -Path $jarPath -Destination (Join-Path $appDir "apiasistente.jar") -Force
    Copy-Item -Path (Join-Path $repoRoot "scripts/check-prerequisites.ps1") -Destination $scriptsDir -Force
    Copy-Item -Path (Join-Path $repoRoot "scripts/run-mysql-container.ps1") -Destination $scriptsDir -Force
    Copy-Item -Path (Join-Path $repoRoot "scripts/run-app-container.ps1") -Destination $scriptsDir -Force
    Copy-Item -Path (Join-Path $repoRoot "scripts/install-guided.ps1") -Destination $scriptsDir -Force
    Copy-Item -Path (Join-Path $repoRoot "scripts/install-guided.cmd") -Destination $scriptsDir -Force
    Copy-Item -Path (Join-Path $repoRoot "docs/installation-architecture.md") -Destination $docsDir -Force
    Copy-Item -Path (Join-Path $repoRoot "README.md") -Destination (Join-Path $bundleDir "README.md") -Force

    $quickstartPath = Join-Path $bundleDir "QUICKSTART.txt"
    $quickstart = @(
        "ApiAsistente - Instalacion rapida",
        "",
        "1) Instala Docker Desktop y Ollama.",
        "2) En PowerShell ejecuta:",
        "   .\\scripts\\install-guided.cmd",
        "",
        "El instalador reutiliza contenedores MySQL existentes cuando detecta uno.",
        "Despues entra en http://localhost:8082/login y completa /setup."
    )
    Set-Content -Path $quickstartPath -Value $quickstart -Encoding UTF8

    New-Item -ItemType Directory -Path $outRoot -Force | Out-Null
    $zipPath = Join-Path $outRoot "$BundleName.zip"
    if (Test-Path $zipPath) {
        Remove-Item -Path $zipPath -Force
    }
    Compress-Archive -Path (Join-Path $bundleDir "*") -DestinationPath $zipPath -CompressionLevel Optimal

    Write-Host "Bundle generado:" -ForegroundColor Green
    Write-Host " - Carpeta: $bundleDir" -ForegroundColor Green
    Write-Host " - ZIP: $zipPath" -ForegroundColor Green
} finally {
    Pop-Location
}
