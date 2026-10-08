<#
  Gera o instalador do NetSpeedBar: dist\NetSpeedBar-Setup-<versão>.exe

    1. Maven compila o projeto (target\NetSpeedBar.jar, já com o JNA dentro)
    2. jpackage cria o NetSpeedBar.exe com um Java mínimo embutido: quem instala NÃO precisa ter Java
    3. Inno Setup empacota tudo num instalador (packaging\NetSpeedBar.iss)

  Só quem GERA o instalador precisa de: JDK 21+ (traz o jpackage), Maven e Inno Setup 6
  (winget install JRSoftware.InnoSetup). A versão vem do <version> do pom.xml.
#>
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$work = Join-Path $root 'target\installer'

function Invoke-Tool([string] $step, [string] $exe, [string[]] $arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$step falhou (código $LASTEXITCODE)"
    }
}

# --- Ferramentas -----------------------------------------------------------------------------------------
$jdkBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path (Get-Command javac -ErrorAction Stop).Source }
$jpackage = Join-Path $jdkBin 'jpackage.exe'
$jdeps = Join-Path $jdkBin 'jdeps.exe'
if (-not (Test-Path $jpackage)) {
    throw "jpackage não encontrado em $jdkBin (precisa do JDK 21 ou mais novo)"
}
$iscc = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) {
    throw 'Inno Setup 6 não encontrado. Instale com: winget install JRSoftware.InnoSetup'
}
[xml] $pom = Get-Content (Join-Path $root 'pom.xml')
$version = $pom.project.version -replace '-SNAPSHOT$', ''

# --- 1. Compilar -----------------------------------------------------------------------------------------
Write-Host "==> 1/3 Compilando a versão $version (Maven)" -ForegroundColor Cyan
Invoke-Tool 'Maven' 'mvn' @('-q', '-f', (Join-Path $root 'pom.xml'), 'clean', 'package')
$jar = Join-Path $root 'target\NetSpeedBar.jar'

# --- 2. Executável com Java embutido ---------------------------------------------------------------------
Write-Host '==> 2/3 Criando o NetSpeedBar.exe com Java embutido (jpackage)' -ForegroundColor Cyan
New-Item -ItemType Directory -Force (Join-Path $work 'input') | Out-Null
Copy-Item $jar (Join-Path $work 'input')

# A DLL nativa do JNA vai ao lado do .jar (sem isso o JNA a extrai para a pasta TEMP a cada início)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $dll = $zip.GetEntry('com/sun/jna/win32-x86-64/jnidispatch.dll')
    [IO.Compression.ZipFileExtensions]::ExtractToFile($dll, (Join-Path $work 'input\jnidispatch.dll'), $true)
} finally {
    $zip.Dispose()
}

# Só os módulos do Java que o programa usa (java.base, java.desktop, java.logging): runtime bem menor
$modules = (& $jdeps --multi-release 21 --ignore-missing-deps --print-module-deps $jar).Trim()
Invoke-Tool 'jpackage' $jpackage @(
    '--type', 'app-image',
    '--name', 'NetSpeedBar',
    '--app-version', $version,
    '--vendor', 'Vitor Brackmann',
    '--description', 'Velocidade de download e upload na barra de tarefas',
    '--icon', (Join-Path $root 'packaging\NetSpeedBar.ico'),
    '--input', (Join-Path $work 'input'),
    '--main-jar', 'NetSpeedBar.jar',
    '--main-class', $pom.project.properties.'exec.mainClass',
    '--add-modules', $modules,
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files --strip-native-commands',
    '--java-options', '-Xmx64m',
    '--java-options', '-XX:+UseSerialGC',
    '--java-options', '-Djna.boot.library.path=$APPDIR',
    '--java-options', '-Djna.nounpack=true',
    '--dest', (Join-Path $work 'image')
)

# --- 3. Instalador ---------------------------------------------------------------------------------------
Write-Host '==> 3/3 Gerando o instalador (Inno Setup)' -ForegroundColor Cyan
Invoke-Tool 'Inno Setup' $iscc @(
    '/Q',
    "/DAppVersion=$version",
    "/DAppImage=$(Join-Path $work 'image\NetSpeedBar')",
    "/DOutputDir=$(Join-Path $root 'dist')",
    (Join-Path $root 'packaging\NetSpeedBar.iss')
)

$setup = Join-Path $root "dist\NetSpeedBar-Setup-$version.exe"
Write-Host ''
Write-Host "Pronto: $setup ($([math]::Round((Get-Item $setup).Length / 1MB, 1)) MB)" -ForegroundColor Green
