<#
.SYNOPSIS
Exporta componentes front reutilizables segun el mapa tematico.

.DESCRIPTION
Lee reusable-components/component-map.json y copia recursos a carpetas por tema.
No modifica codigo fuente original, solo genera artefactos de reutilizacion.
#>
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"

$mapPath = Join-Path $RepoRoot "reusable-components/component-map.json"
$themesRoot = Join-Path $RepoRoot "reusable-components/themes"

if (!(Test-Path $mapPath)) {
    throw "No existe component-map.json en: $mapPath"
}

$map = Get-Content -Path $mapPath -Raw | ConvertFrom-Json

foreach ($themeProperty in $map.PSObject.Properties) {
    $theme = $themeProperty.Name
    $themeDir = Join-Path $themesRoot $theme
    New-Item -ItemType Directory -Force -Path $themeDir | Out-Null

    foreach ($relativePath in @($themeProperty.Value)) {
        $sourcePath = Join-Path $RepoRoot $relativePath
        if (!(Test-Path $sourcePath)) {
            Write-Warning "No encontrado: $relativePath"
            continue
        }

        $fileName = Split-Path -Path $sourcePath -Leaf
        $targetPath = Join-Path $themeDir $fileName
        Copy-Item -Path $sourcePath -Destination $targetPath -Force
        Write-Host "[copied] $relativePath -> reusable-components/themes/$theme/$fileName"
    }
}

Write-Host ""
Write-Host "Export finalizado."
Write-Host "Kit disponible en: reusable-components/themes/"
