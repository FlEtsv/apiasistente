[CmdletBinding()]
param(
    [string]$ContainerName = "apiasistente_mysql",
    [string]$UseExistingContainer = "",
    [string]$NetworkName = "apiasistente_net",
    [string]$VolumeName = "apiasistente_mysql_data",
    [string]$MySqlVersion = "8.4",
    [int]$HostPort = 3306,
    [string]$DbName = "apiasistente_db",
    [string]$DbUser = "apiuser",
    [string]$DbPassword = "apipassword",
    [string]$RootPassword = "rootpassword",
    [int]$WaitTimeoutSec = 180,
    [switch]$Recreate,
    [switch]$SkipDbInit
)

$ErrorActionPreference = "Stop"

function Write-WarnLine([string]$message) {
    Write-Host "[WARN] $message" -ForegroundColor Yellow
}

function Invoke-Docker([string[]]$Args) {
    $output = & docker @Args 2>&1
    if ($LASTEXITCODE -ne 0) {
        $text = ($output | Out-String).Trim()
        throw "docker $($Args -join ' ') fallo: $text"
    }
    return ($output | Out-String).Trim()
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

function Ensure-Volume([string]$Name) {
    $existing = & docker volume ls --format "{{.Name}}" | Where-Object { $_ -eq $Name }
    if (-not $existing) {
        Write-Host "Creando volumen Docker '$Name'..."
        Invoke-Docker @("volume", "create", $Name) | Out-Null
    } else {
        Write-Host "Volumen Docker '$Name' ya existe."
    }
}

function Container-Exists([string]$Name) {
    & docker container inspect $Name *> $null
    return ($LASTEXITCODE -eq 0)
}

function Ensure-ContainerRunning([string]$Name) {
    if (-not (Container-Exists -Name $Name)) {
        throw "No existe el contenedor '$Name'."
    }
    $state = Invoke-Docker @("inspect", "-f", "{{.State.Status}}", $Name)
    if ($state -ne "running") {
        Write-Host "Iniciando contenedor existente '$Name'..."
        Invoke-Docker @("start", $Name) | Out-Null
    }
}

function Connect-ToNetwork([string]$Container, [string]$Network) {
    $result = & docker network connect $Network $Container 2>&1
    if ($LASTEXITCODE -ne 0) {
        $text = ($result | Out-String).Trim()
        if ($text -match "already exists") {
            return
        }
        throw "No se pudo conectar '$Container' a la red '$Network': $text"
    }
}

function Get-ContainerHealthOrState([string]$Name) {
    $status = & docker inspect -f "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $Name 2>$null
    if ($LASTEXITCODE -ne 0) {
        return ""
    }
    return ($status | Out-String).Trim().ToLowerInvariant()
}

function Wait-ContainerReady([string]$Name, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds([Math]::Max(30, $TimeoutSec))
    while ((Get-Date) -lt $deadline) {
        $status = Get-ContainerHealthOrState -Name $Name
        if ($status -eq "healthy" -or $status -eq "running") {
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "Contenedor '$Name' no alcanzo estado listo dentro de $TimeoutSec segundos."
}

function Ensure-DatabaseAccess([string]$Name,
                               [string]$RootPasswordArg,
                               [string]$DbNameArg,
                               [string]$DbUserArg,
                               [string]$DbPasswordArg,
                               [switch]$IgnoreErrors) {
    $escDbUser = $DbUserArg.Replace("'", "''")
    $escDbPassword = $DbPasswordArg.Replace("'", "''")
    $escDbName = ($DbNameArg -replace "[^a-zA-Z0-9_]", "")
    if ([string]::IsNullOrWhiteSpace($escDbName)) {
        throw "Nombre de base de datos invalido: '$DbNameArg'"
    }
    $sql = "CREATE DATABASE IF NOT EXISTS $escDbName; CREATE USER IF NOT EXISTS '$escDbUser'@'%' IDENTIFIED BY '$escDbPassword'; GRANT ALL PRIVILEGES ON $escDbName.* TO '$escDbUser'@'%'; FLUSH PRIVILEGES;"

    try {
        Invoke-Docker @("exec", $Name, "mysql", "-uroot", "-p$RootPasswordArg", "-e", $sql) | Out-Null
    } catch {
        if ($IgnoreErrors) {
            Write-WarnLine "No se pudo ejecutar SQL de inicializacion en '$Name'. Se asume DB/usuario ya existen."
            Write-WarnLine $_.Exception.Message
            return
        }
        throw
    }
}

Write-Host "== Provisioning MySQL container =="
Invoke-Docker @("info") | Out-Null

Ensure-Network -Name $NetworkName

$selectedContainer = if ([string]::IsNullOrWhiteSpace($UseExistingContainer)) { $ContainerName } else { $UseExistingContainer.Trim() }
$isReuseMode = -not [string]::IsNullOrWhiteSpace($UseExistingContainer)

if ($isReuseMode) {
    Write-Host "Reutilizando contenedor MySQL existente '$selectedContainer'."
    Ensure-ContainerRunning -Name $selectedContainer
    Connect-ToNetwork -Container $selectedContainer -Network $NetworkName
    Wait-ContainerReady -Name $selectedContainer -TimeoutSec $WaitTimeoutSec

    if (-not $SkipDbInit) {
        Ensure-DatabaseAccess -Name $selectedContainer `
            -RootPasswordArg $RootPassword `
            -DbNameArg $DbName `
            -DbUserArg $DbUser `
            -DbPasswordArg $DbPassword `
            -IgnoreErrors
    }

    Write-Host "MySQL reutilizado en contenedor '$selectedContainer' (DB=$DbName, USER=$DbUser)." -ForegroundColor Green
    Write-Output "MYSQL_CONTAINER=$selectedContainer"
    exit 0
}

Ensure-Volume -Name $VolumeName

if (Container-Exists -Name $selectedContainer) {
    if ($Recreate) {
        Write-Host "Eliminando contenedor existente '$selectedContainer'..."
        Invoke-Docker @("rm", "-f", $selectedContainer) | Out-Null
    } else {
        Ensure-ContainerRunning -Name $selectedContainer
    }
}

if (-not (Container-Exists -Name $selectedContainer)) {
    Write-Host "Creando contenedor MySQL '$selectedContainer'..."
    Invoke-Docker @(
        "run", "-d",
        "--name", $selectedContainer,
        "--network", $NetworkName,
        "-p", "$HostPort`:3306",
        "-e", "MYSQL_DATABASE=$DbName",
        "-e", "MYSQL_USER=$DbUser",
        "-e", "MYSQL_PASSWORD=$DbPassword",
        "-e", "MYSQL_ROOT_PASSWORD=$RootPassword",
        "--health-cmd", 'mysqladmin ping -h localhost -uroot -p$MYSQL_ROOT_PASSWORD --silent',
        "--health-interval", "10s",
        "--health-timeout", "5s",
        "--health-retries", "20",
        "-v", "$VolumeName`:/var/lib/mysql",
        "mysql:$MySqlVersion"
    ) | Out-Null
}

Write-Host "Esperando a que MySQL este listo..."
Wait-ContainerReady -Name $selectedContainer -TimeoutSec $WaitTimeoutSec

if (-not $SkipDbInit) {
    Ensure-DatabaseAccess -Name $selectedContainer `
        -RootPasswordArg $RootPassword `
        -DbNameArg $DbName `
        -DbUserArg $DbUser `
        -DbPasswordArg $DbPassword
}

Write-Host "MySQL listo en localhost:$HostPort (DB=$DbName, USER=$DbUser)." -ForegroundColor Green
Write-Host "Las tablas de la app se crean automaticamente al arrancar el backend Spring." -ForegroundColor Green
Write-Output "MYSQL_CONTAINER=$selectedContainer"
