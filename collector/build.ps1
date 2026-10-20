# Build helper for the Surveyor. Usage:  .\build.ps1 [gradle tasks...]   (default: assembleDebug testDebugUnitTest)
# Uses a portable JDK 17 from C:\Dev\tools\jdk and a short TEMP folder: Java's Windows "loopback" pipe is a
# Unix-domain socket file in TEMP, and very long TEMP paths make Gradle fail with "Unable to establish loopback".
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks)
$ErrorActionPreference = 'Stop'
if (-not $Tasks) { $Tasks = @('assembleDebug', 'testDebugUnitTest') }
$jdk = Get-ChildItem 'C:\Dev\tools\jdk' -Directory -ErrorAction SilentlyContinue | Where-Object Name -like 'jdk-17*' | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
New-Item -ItemType Directory -Force 'C:\Dev\tmp' | Out-Null
$env:TEMP = 'C:\Dev\tmp'; $env:TMP = 'C:\Dev\tmp'
Push-Location $PSScriptRoot
try { & .\gradlew.bat --console=plain @Tasks; exit $LASTEXITCODE } finally { Pop-Location }
