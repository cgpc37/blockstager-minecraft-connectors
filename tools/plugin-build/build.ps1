#requires -Version 5.1
<#
.SYNOPSIS
    Compila BlockstagerStudioPlugin.jar y SelfTest usando únicamente JDK 21 (sin Gradle/Maven).
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch {
    # En PowerShell 7+ TLS 1.2/1.3 ya está habilitado por defecto
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$PluginRoot = Join-Path $RepoRoot "minecraft-plugin"
$BuildToolRoot = Join-Path $RepoRoot "tools\plugin-build"
$LibDir = Join-Path $BuildToolRoot "lib"
$BuildDir = Join-Path $BuildToolRoot "build"
$PluginClassesDir = Join-Path $BuildDir "classes"
$SelfTestClassesDir = Join-Path $BuildDir "selftest"
$OutputJar = Join-Path $PluginRoot "BlockstagerStudioPlugin.jar"

New-Item -ItemType Directory -Force -Path $LibDir, $PluginClassesDir, $SelfTestClassesDir | Out-Null

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
    Write-Host "[plugin-build] Descargando $(Split-Path -Leaf $Destination) desde $Url ..."
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

function Resolve-PaperApiJarUrl {
    $baseUrl = "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/1.21.4-R0.1-SNAPSHOT"
    $metadataUrl = "$baseUrl/maven-metadata.xml"
    Write-Host "[plugin-build] Consultando metadata de Paper API: $metadataUrl"
    $resp = Invoke-WebRequest -Uri $metadataUrl -UseBasicParsing -TimeoutSec 30
    [xml]$xml = $resp.Content

    $versionValue = $null
    $snapshotVersions = $xml.metadata.versioning.snapshotVersions.snapshotVersion
    if ($snapshotVersions) {
        foreach ($sv in $snapshotVersions) {
            $hasClassifier = ($null -ne $sv.PSObject.Properties["classifier"]) -and (-not [string]::IsNullOrWhiteSpace($sv.classifier))
            if ($sv.extension -eq "jar" -and -not $hasClassifier) {
                $versionValue = $sv.value
                break
            }
        }
    }

    if (-not $versionValue) {
        $ts = $xml.metadata.versioning.snapshot.timestamp
        $bn = $xml.metadata.versioning.snapshot.buildNumber
        if ($ts -and $bn) {
            $versionValue = "1.21.4-R0.1-$ts-$bn"
        }
    }

    if (-not $versionValue) {
        throw "No se pudo resolver la versión timestamped de paper-api en $metadataUrl"
    }

    return "$baseUrl/paper-api-$versionValue.jar"
}

$GsonJar = Join-Path $LibDir "gson-2.11.0.jar"
$PaperApiJar = Join-Path $LibDir "paper-api-1.21.4-R0.1-SNAPSHOT.jar"
$AdventureKeyJar = Join-Path $LibDir "adventure-key-4.17.0.jar"
$AdventureApiJar = Join-Path $LibDir "adventure-api-4.17.0.jar"
$ExaminationApiJar = Join-Path $LibDir "examination-api-1.3.0.jar"
$JetbrainsAnnotationsJar = Join-Path $LibDir "annotations-24.1.0.jar"
$BungeeChatJar = Join-Path $LibDir "bungeecord-chat-1.21-R0.4.jar"

$DownloadErrors = New-Object System.Collections.Generic.List[string]

# 1. Descargar Gson (necesario tanto para el plugin como para SelfTest)
try {
    Download-IfMissing `
        -Url "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar" `
        -Destination $GsonJar | Out-Null
} catch {
    $DownloadErrors.Add("gson-2.11.0.jar: $($_.Exception.Message)")
}

# 2. Descargar Paper API y APIs transitivas compileOnly mínimas (solo si faltan)
try {
    if (-not (Test-Path $PaperApiJar)) {
        $paperUrl = Resolve-PaperApiJarUrl
        Download-IfMissing -Url $paperUrl -Destination $PaperApiJar | Out-Null
    }
    Download-IfMissing `
        -Url "https://repo1.maven.org/maven2/net/kyori/adventure-key/4.17.0/adventure-key-4.17.0.jar" `
        -Destination $AdventureKeyJar | Out-Null
    Download-IfMissing `
        -Url "https://repo1.maven.org/maven2/net/kyori/adventure-api/4.17.0/adventure-api-4.17.0.jar" `
        -Destination $AdventureApiJar | Out-Null
    Download-IfMissing `
        -Url "https://repo1.maven.org/maven2/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar" `
        -Destination $ExaminationApiJar | Out-Null
    Download-IfMissing `
        -Url "https://repo1.maven.org/maven2/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar" `
        -Destination $JetbrainsAnnotationsJar | Out-Null
    Download-IfMissing `
        -Url "https://repo.papermc.io/repository/maven-public/net/md-5/bungeecord-chat/1.21-R0.4/bungeecord-chat-1.21-R0.4.jar" `
        -Destination $BungeeChatJar | Out-Null
} catch {
    $DownloadErrors.Add("paper-api / compileOnly deps: $($_.Exception.Message)")
}

# 3. Compilar SelfTest + WsBridgeClient (siempre que Gson exista)
if (Test-Path $GsonJar) {
    Write-Host "[plugin-build] Compilando WsBridgeClient.java + RemoteActionPolicy.java + SelfTest.java..."
    if (Test-Path $SelfTestClassesDir) {
        Remove-Item -Recurse -Force $SelfTestClassesDir
    }
    New-Item -ItemType Directory -Force -Path $SelfTestClassesDir | Out-Null

    $wsBridgeSrc = Join-Path $PluginRoot "src\main\java\dev\blockstager\plugin\WsBridgeClient.java"
    $policySrc = Join-Path $PluginRoot "src\main\java\dev\blockstager\plugin\RemoteActionPolicy.java"
    $selfTestSrc = Join-Path $PluginRoot "src\test\java\dev\blockstager\plugin\SelfTest.java"

    & javac --release 21 -encoding UTF-8 -Xlint:deprecation -cp $GsonJar -d $SelfTestClassesDir $wsBridgeSrc $policySrc $selfTestSrc
    if ($LASTEXITCODE -ne 0) {
        throw "Falló la compilación de SelfTest (exit=$LASTEXITCODE)"
    }
    Write-Host "[plugin-build] SelfTest compilado en $SelfTestClassesDir"
} else {
    Write-Warning "[plugin-build] No se pudo compilar SelfTest porque falta gson-2.11.0.jar."
}

# 4. Compilar plugin completo y empaquetar BlockstagerStudioPlugin.jar
if ((Test-Path $PaperApiJar) -and (Test-Path $GsonJar)) {
    Write-Host "[plugin-build] Compilando fuentes del plugin (Paper 1.21+)..."
    if (Test-Path $PluginClassesDir) {
        Remove-Item -Recurse -Force $PluginClassesDir
    }
    New-Item -ItemType Directory -Force -Path $PluginClassesDir | Out-Null

    $cpEntries = @($PaperApiJar, $GsonJar)
    foreach ($optJar in @($AdventureKeyJar, $AdventureApiJar, $ExaminationApiJar, $JetbrainsAnnotationsJar, $BungeeChatJar)) {
        if (Test-Path $optJar) {
            $cpEntries += $optJar
        }
    }
    $classpath = $cpEntries -join ";"

    $javaSources = Get-ChildItem -Path (Join-Path $PluginRoot "src\main\java") -Recurse -Filter "*.java" |
        ForEach-Object { $_.FullName }

    & javac --release 21 -encoding UTF-8 -Xlint:deprecation -cp $classpath -d $PluginClassesDir $javaSources
    if ($LASTEXITCODE -ne 0) {
        throw "Falló la compilación del plugin (exit=$LASTEXITCODE)"
    }

    $resourcesDir = Join-Path $PluginRoot "src\main\resources"
    if (Test-Path $resourcesDir) {
        Copy-Item -Path (Join-Path $resourcesDir "*") -Destination $PluginClassesDir -Recurse -Force
    }

    if (Test-Path $OutputJar) {
        Remove-Item -Force $OutputJar
    }

    & jar cf $OutputJar -C $PluginClassesDir .
    if ($LASTEXITCODE -ne 0) {
        throw "Falló la creación del JAR (exit=$LASTEXITCODE)"
    }

    $jarInfo = Get-Item $OutputJar
    Write-Host "[plugin-build] JAR generado correctamente: $($jarInfo.FullName) ($($jarInfo.Length) bytes)"
} else {
    Write-Warning "[plugin-build] No se generó BlockstagerStudioPlugin.jar por falta de dependencias descargables."
    foreach ($err in $DownloadErrors) {
        Write-Warning "  - $err"
    }
    exit 1
}

if ($DownloadErrors.Count -gt 0) {
    Write-Warning "[plugin-build] Advertencias durante la descarga:"
    foreach ($err in $DownloadErrors) {
        Write-Warning "  - $err"
    }
}
