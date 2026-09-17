# Compile and deploy GexbotMajors into MotiveWave Extensions

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$buildDir = Join-Path $projectRoot "build"
$libDir = Join-Path $projectRoot "lib"
$srcDir = Join-Path $projectRoot "src"
$classesDir = Join-Path $buildDir "classes"
$extDir = Join-Path $env:USERPROFILE "MotiveWave Extensions"
$devDir = Join-Path $extDir "dev"

$jdkHomeFile = Join-Path $buildDir "jdk_home.txt"
$candidates = @()
if (Test-Path $jdkHomeFile) { $candidates += (Get-Content $jdkHomeFile -Raw).Trim() }
$candidates += @(
  "C:\Users\hauat\Java\jdk-27",
  "C:\Program Files\Java\jdk-27",
  "C:\Program Files\Java\jdk-26",
  "C:\Program Files\Java\jdk-25",
  $env:JAVA_HOME
) | Where-Object { $_ -and (Test-Path $_) }

$jdkHome = $candidates | Where-Object { Test-Path (Join-Path $_ "bin\javac.exe") } | Select-Object -First 1
if ($jdkHome) {
  $env:JAVA_HOME = $jdkHome
  $env:Path = "$jdkHome\bin;" + $env:Path
  Write-Host "Using JDK: $jdkHome"
} else {
  Write-Host "WARNING: no JDK 25+ found, using default java on PATH"
}

Write-Host "Project: $projectRoot"
& java -version

$sdk = Join-Path $libDir "mwave_sdk.jar"
if (-not (Test-Path $sdk)) { throw "mwave_sdk.jar missing in lib/" }

Remove-Item -Recurse -Force $classesDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

$sources = @(Get-ChildItem -Path $srcDir -Recurse -Filter "*.java" |
  Where-Object { $_.FullName -match '[\\/]gexbot[\\/]' } |
  ForEach-Object { $_.FullName })

if ($sources.Count -lt 1) { throw "No gexbot java sources found" }
Write-Host "Compiling $($sources.Count) files..."

& javac -encoding UTF-8 -g --release 26 -cp $sdk -d $classesDir @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }

Remove-Item -Recurse -Force $devDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $devDir | Out-Null
Copy-Item -Recurse -Force (Join-Path $classesDir "*") $devDir

$jarPath = Join-Path $extDir "gexbot_custom_majors.jar"
$jarOk = $false
try {
  if (Test-Path $jarPath) { Remove-Item $jarPath -Force -ErrorAction Stop }
  & jar cf $jarPath -C $classesDir .
  $jarOk = $true
  Write-Host "OK jar: $jarPath"
}
catch {
  $altJar = Join-Path $extDir "gexbot_custom_majors_v2.jar"
  & jar cf $altJar -C $classesDir .
  Write-Host "WARN jar locked. Wrote $altJar instead. Close MotiveWave and redeploy."
}

New-Item -ItemType Directory -Force -Path $extDir | Out-Null
[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString() |
  Set-Content -NoNewline (Join-Path $extDir ".last_updated")

Write-Host "OK classes: $devDir"
Write-Host "Close/reopen MotiveWave, then set: Custom key + Ticker Auto + Conversion Auto + 0DTE"
