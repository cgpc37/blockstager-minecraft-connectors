#requires -Version 5.1
<#
.SYNOPSIS
    Compila BlockstagerStudioFabric.jar y FabricSelfTest usando únicamente JDK 21 (sin Gradle/Maven).
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch {
    # PowerShell 7+ ya habilita TLS 1.2/1.3 por defecto
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ModRoot = Join-Path $RepoRoot "minecraft-fabric-mod"
$BuildToolRoot = Join-Path $RepoRoot "tools\fabric-build"
$LibDir = Join-Path $BuildToolRoot "lib"
$BuildDir = Join-Path $BuildToolRoot "build"
$ModClassesDir = Join-Path $BuildDir "classes"
$SelfTestClassesDir = Join-Path $BuildDir "selftest"
$OutputJar = Join-Path $ModRoot "BlockstagerStudioFabric.jar"

New-Item -ItemType Directory -Force -Path $LibDir, $ModClassesDir, $SelfTestClassesDir | Out-Null

function Assert-Tool($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Herramienta requerida no encontrada en PATH: $name"
    }
}

Assert-Tool "java"
Assert-Tool "javac"
Assert-Tool "jar"

function Download-IfMissing {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    if (Test-Path $Destination) {
        return $true
    }
    Write-Host "[fabric-build] Descargando $(Split-Path -Leaf $Destination) desde $Url ..."
    $tmp = "$Destination.part"
    if (Test-Path $tmp) {
        Remove-Item -Force $tmp
    }
    try {
        Invoke-WebRequest -Uri $Url -OutFile $tmp -UseBasicParsing -TimeoutSec 60
        Move-Item -Force $tmp $Destination
        return $true
    } catch {
        if (Test-Path $tmp) {
            Remove-Item -Force $tmp
        }
        throw
    }
}

$GsonJar = Join-Path $LibDir "gson-2.11.0.jar"
$PluginGsonJar = Join-Path $RepoRoot "tools\plugin-build\lib\gson-2.11.0.jar"
if ((-not (Test-Path $GsonJar)) -and (Test-Path $PluginGsonJar)) {
    Copy-Item -Force $PluginGsonJar $GsonJar
}

$FabricLoaderJar = Join-Path $LibDir "fabric-loader-0.16.10.jar"
$BrigadierJar = Join-Path $LibDir "brigadier-1.3.10.jar"
$Slf4jJar = Join-Path $LibDir "slf4j-api-2.0.16.jar"

$DownloadErrors = New-Object System.Collections.Generic.List[string]

try {
    Download-IfMissing `
        -Url "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar" `
        -Destination $GsonJar | Out-Null
    Download-IfMissing `
        -Url "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar" `
        -Destination $FabricLoaderJar | Out-Null
    Download-IfMissing `
        -Url "https://libraries.minecraft.net/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar" `
        -Destination $BrigadierJar | Out-Null
    Download-IfMissing `
        -Url "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar" `
        -Destination $Slf4jJar | Out-Null
} catch {
    $DownloadErrors.Add($_.Exception.Message)
}

# 1. Compilar FabricSelfTest (clases puras sin dependencias de Fabric/Minecraft)
if (Test-Path $GsonJar) {
    Write-Host "[fabric-build] Compilando FabricSelfTest..."
    if (Test-Path $SelfTestClassesDir) {
        Remove-Item -Recurse -Force $SelfTestClassesDir
    }
    New-Item -ItemType Directory -Force -Path $SelfTestClassesDir | Out-Null

    $pureSources = @(
        (Join-Path $ModRoot "src\main\java\dev\blockstager\fabric\FabricModConfig.java"),
        (Join-Path $ModRoot "src\main\java\dev\blockstager\fabric\FabricWsBridgeClient.java"),
        (Join-Path $ModRoot "src\main\java\dev\blockstager\fabric\ClientActionPolicy.java"),
        (Join-Path $ModRoot "src\main\java\dev\blockstager\fabric\ClientStateTracker.java"),
        (Join-Path $ModRoot "src\test\java\dev\blockstager\fabric\FabricSelfTest.java")
    )

    & javac --release 21 -encoding UTF-8 -Xlint:deprecation -cp $GsonJar -d $SelfTestClassesDir $pureSources
    if ($LASTEXITCODE -ne 0) {
        throw "Falló la compilación de FabricSelfTest (exit=$LASTEXITCODE)"
    }
    Write-Host "[fabric-build] FabricSelfTest compilado en $SelfTestClassesDir"
} else {
    throw "Falta gson-2.11.0.jar para compilar FabricSelfTest."
}

# 2. Compilar el mod Fabric 1.21+ completo y empaquetar BlockstagerStudioFabric.jar
if ((Test-Path $FabricLoaderJar) -and (Test-Path $BrigadierJar) -and (Test-Path $GsonJar)) {
    Write-Host "[fabric-build] Compilando fuentes del mod cliente (Fabric 1.21+)..."
    if (Test-Path $ModClassesDir) {
        Remove-Item -Recurse -Force $ModClassesDir
    }
    New-Item -ItemType Directory -Force -Path $ModClassesDir | Out-Null

    $cpEntries = @($FabricLoaderJar, $BrigadierJar, $GsonJar)
    if (Test-Path $Slf4jJar) {
        $cpEntries += $Slf4jJar
    }
    $classpath = $cpEntries -join ";"

    $javaSources = Get-ChildItem -Path (Join-Path $ModRoot "src\main\java") -Recurse -Filter "*.java" |
        ForEach-Object { $_.FullName }

    & javac --release 21 -encoding UTF-8 -Xlint:deprecation -cp $classpath -d $ModClassesDir $javaSources
    if ($LASTEXITCODE -ne 0) {
        throw "Falló la compilación del mod Fabric (exit=$LASTEXITCODE)"
    }

    $resourcesDir = Join-Path $ModRoot "src\main\resources"
    if (Test-Path $resourcesDir) {
        Copy-Item -Path (Join-Path $resourcesDir "*") -Destination $ModClassesDir -Recurse -Force
    }

    if (Test-Path $OutputJar) {
        Remove-Item -Force $OutputJar
    }

    & jar cf $OutputJar -C $ModClassesDir .
    if ($LASTEXITCODE -ne 0) {
        throw "Falló la creación de BlockstagerStudioFabric.jar (exit=$LASTEXITCODE)"
    }

    # Auditoría de higiene: verificar 0 clases/assets de Mojang dentro del JAR
    $jarEntries = & jar tf $OutputJar
    $forbidden = $jarEntries | Where-Object { $_ -match "^(net/minecraft/|assets/minecraft/|com/mojang/)" }
    if ($forbidden) {
        throw "Violación de licencia detectada dentro del JAR Fabric: $($forbidden -join ', ')"
    }

    $jarInfo = Get-Item $OutputJar
    Write-Host "[fabric-build] JAR generado y verificado (0 entradas Mojang): $($jarInfo.FullName) ($($jarInfo.Length) bytes)"
} else {
    foreach ($err in $DownloadErrors) {
        Write-Warning "  - $err"
    }
    throw "No se pudo compilar BlockstagerStudioFabric.jar por falta de dependencias compileOnly."
}
