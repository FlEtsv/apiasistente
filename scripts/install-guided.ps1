[CmdletBinding()]
param(
    [switch]$NonInteractive,
    [string]$OllamaBaseUrl = "http://host.docker.internal:11434/api",
    [int]$AppPort = 8082,
    [int]$MySqlPort = 3306,
    [string]$DbName = "apiasistente_db",
    [string]$DbUser = "apiuser",
    [string]$DbPassword = "apipassword",
    [string]$RootPassword = "rootpassword",
    [string]$BootstrapAdminUsername = "admin",
    [string]$BootstrapAdminPassword = "",
    [string]$ExistingMySqlContainer = "",
    [switch]$SkipOllamaCheck,
    [switch]$RecreateAppContainer,
    [switch]$RecreateMySqlContainer
)

$ErrorActionPreference = "Stop"

function Read-WithDefault([string]$label, [string]$defaultValue) {
    $value = Read-Host "$label [$defaultValue]"
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $defaultValue
    }
    return $value.Trim()
}

function To-IntOrDefault([string]$raw, [int]$defaultValue) {
    $number = 0
    if ([int]::TryParse($raw, [ref]$number)) {
        return $number
    }
    return $defaultValue
}

function Save-InstallEnv([hashtable]$cfg, [string]$targetPath) {
    $lines = @(
        "OLLAMA_BASE_URL=$($cfg.OllamaBaseUrl)",
        "MYSQL_PORT=$($cfg.MySqlPort)",
        "MYSQL_DB=$($cfg.DbName)",
        "MYSQL_USER=$($cfg.DbUser)",
        "MYSQL_PASSWORD=$($cfg.DbPassword)",
        "MYSQL_CONTAINER=$($cfg.MySqlContainer)",
        "BOOTSTRAP_ADMIN_USERNAME=$($cfg.BootstrapAdminUsername)",
        "BOOTSTRAP_ADMIN_PASSWORD=$($cfg.BootstrapAdminPassword)",
        "APP_PORT=$($cfg.AppPort)"
    )
    Set-Content -Path $targetPath -Value $lines -Encoding UTF8
}

function Get-MySqlCandidates() {
    $rows = & docker ps -a --format "{{.Names}}|{{.Image}}|{{.Status}}" 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $rows) {
        return @()
    }
    $candidates = @()
    foreach ($row in $rows) {
        if ([string]::IsNullOrWhiteSpace($row)) { continue }
        $parts = $row.Split("|")
        if ($parts.Count -lt 2) { continue }
        $name = $parts[0].Trim()
        $image = $parts[1].Trim()
        $status = if ($parts.Count -ge 3) { $parts[2].Trim() } else { "" }
        if ($image -match "(?i)(mysql|mariadb)") {
            $candidates += [pscustomobject]@{
                Name = $name
                Image = $image
                Status = $status
            }
        }
    }
    return $candidates
}

function Select-MySqlContainer([switch]$IsNonInteractive, [string]$PreferredName) {
    if (-not [string]::IsNullOrWhiteSpace($PreferredName)) {
        return $PreferredName.Trim()
    }

    $candidates = @(Get-MySqlCandidates)
    if ($candidates.Count -eq 0) {
        return ""
    }

    $own = $candidates | Where-Object { $_.Name -eq "apiasistente_mysql" } | Select-Object -First 1
    if ($null -ne $own) {
        return $own.Name
    }

    if ($candidates.Count -eq 1) {
        return $candidates[0].Name
    }

    if ($IsNonInteractive) {
        throw "Se detectaron varios contenedores MySQL. Usa -ExistingMySqlContainer para elegir uno."
    }

    Write-Host "Se detectaron varios contenedores MySQL/MariaDB:"
    for ($i = 0; $i -lt $candidates.Count; $i++) {
        $item = $candidates[$i]
        Write-Host "  [$i] $($item.Name) | $($item.Image) | $($item.Status)"
    }
    $answer = Read-Host "Elige indice para reutilizar o Enter para crear apiasistente_mysql"
    if ([string]::IsNullOrWhiteSpace($answer)) {
        return ""
    }
    $idx = -1
    if (-not [int]::TryParse($answer, [ref]$idx)) {
        throw "Indice invalido: '$answer'"
    }
    if ($idx -lt 0 -or $idx -ge $candidates.Count) {
        throw "Indice fuera de rango: $idx"
    }
    return $candidates[$idx].Name
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$checkScript = Join-Path $PSScriptRoot "check-prerequisites.ps1"
$mysqlScript = Join-Path $PSScriptRoot "run-mysql-container.ps1"
$appScript = Join-Path $PSScriptRoot "run-app-container.ps1"

if (-not $NonInteractive) {
    Write-Host "== Instalacion guiada ApiAsistente ==" -ForegroundColor Cyan
    Write-Host "Pulsa Enter para aceptar cada valor por defecto." -ForegroundColor Cyan
    $OllamaBaseUrl = Read-WithDefault "Ollama base URL" $OllamaBaseUrl
    $AppPort = To-IntOrDefault (Read-WithDefault "Puerto web de la app" "$AppPort") $AppPort
    $MySqlPort = To-IntOrDefault (Read-WithDefault "Puerto host de MySQL" "$MySqlPort") $MySqlPort
    $DbName = Read-WithDefault "Nombre de base de datos" $DbName
    $DbUser = Read-WithDefault "Usuario MySQL de la app" $DbUser
    $DbPassword = Read-WithDefault "Password MySQL de la app" $DbPassword
    $RootPassword = Read-WithDefault "Password root MySQL (si aplica)" $RootPassword
    $BootstrapAdminUsername = Read-WithDefault "Usuario admin bootstrap" $BootstrapAdminUsername
    $BootstrapAdminPassword = Read-WithDefault "Password admin bootstrap (vacio = auto)" $BootstrapAdminPassword
}

Write-Host "1) Ejecutando preflight..."
& $checkScript -OllamaBaseUrl $OllamaBaseUrl -SkipOllamaCheck:$SkipOllamaCheck
if ($LASTEXITCODE -ne 0) {
    throw "Preflight fallo. Corrige los errores y reintenta."
}

$resolvedMySqlContainer = Select-MySqlContainer -IsNonInteractive:$NonInteractive -PreferredName $ExistingMySqlContainer
$reuseExistingMySql = -not [string]::IsNullOrWhiteSpace($resolvedMySqlContainer)

if ($reuseExistingMySql) {
    Write-Host "Se reutilizara el contenedor MySQL existente: $resolvedMySqlContainer" -ForegroundColor Yellow
} else {
    $resolvedMySqlContainer = "apiasistente_mysql"
    Write-Host "No se detecto contenedor MySQL reutilizable; se creara '$resolvedMySqlContainer'."
}

Write-Host "2) Provisionando MySQL..."
if ($reuseExistingMySql) {
    & $mysqlScript `
        -UseExistingContainer $resolvedMySqlContainer `
        -DbName $DbName `
        -DbUser $DbUser `
        -DbPassword $DbPassword `
        -RootPassword $RootPassword `
        -SkipDbInit
} else {
    & $mysqlScript `
        -ContainerName $resolvedMySqlContainer `
        -HostPort $MySqlPort `
        -DbName $DbName `
        -DbUser $DbUser `
        -DbPassword $DbPassword `
        -RootPassword $RootPassword `
        -Recreate:$RecreateMySqlContainer
}
if ($LASTEXITCODE -ne 0) {
    throw "No se pudo provisionar MySQL."
}

$cfg = @{
    OllamaBaseUrl = $OllamaBaseUrl
    AppPort = $AppPort
    MySqlPort = $MySqlPort
    DbName = $DbName
    DbUser = $DbUser
    DbPassword = $DbPassword
    MySqlContainer = $resolvedMySqlContainer
    BootstrapAdminUsername = $BootstrapAdminUsername
    BootstrapAdminPassword = $BootstrapAdminPassword
}
$envPath = Join-Path $repoRoot ".install.env"
Save-InstallEnv -cfg $cfg -targetPath $envPath
Write-Host "Configuracion guardada en $envPath"

Write-Host "3) Provisionando API..."
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
if ($LASTEXITCODE -ne 0) {
    throw "No se pudo provisionar la API."
}

Write-Host ""
Write-Host "Instalacion completada." -ForegroundColor Green
Write-Host "Siguiente paso: entrar en http://localhost:$AppPort/login y despues completar /setup." -ForegroundColor Green
Write-Host "Nota: el scraper es opcional. Puedes dejarlo desactivado y activar/integrar luego." -ForegroundColor Green
