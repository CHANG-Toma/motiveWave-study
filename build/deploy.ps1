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
  "C:\Users\CHANG Toma\.jdks\jdk-26",
  "C:\Users\CHANG Toma\.jdks\openjdk-19.0.2",
  "C:\Users\CHANG Toma\.jdks\openjdk-17",
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
  Write-Host "WARNING: no JDK found, using default java on PATH"
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

# mwave_sdk.jar is Java 26 bytecode; compile with matching release.
& javac -encoding UTF-8 -g --release 26 -cp $sdk -d $classesDir @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }

Remove-Item -Recurse -Force $devDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $devDir | Out-Null
Copy-Item -Recurse -Force (Join-Path $classesDir "*") $devDir

# Remove any old custom jars (single canonical name: gexbot_majors.jar)
foreach ($old in @("gexbot_custom_majors.jar", "gexbot_custom_majors_v2.jar")) {
  Remove-Item (Join-Path $extDir $old) -Force -ErrorAction SilentlyContinue
}

$jarPath = Join-Path $extDir "gexbot_majors.jar"
try {
  if (Test-Path $jarPath) { Remove-Item $jarPath -Force -ErrorAction Stop }
  & jar cf $jarPath -C $classesDir .
  if ($LASTEXITCODE -ne 0) { throw "jar creation failed" }
  Write-Host "OK jar: $jarPath"
}
catch {
  Write-Host "WARN jar locked by MotiveWave - skipped jar replace."
  Write-Host "dev/ + .last_updated were written so MotiveWave can hot-reload."
  Write-Host "If the chart does not update, remove/re-add the study (no need to close MotiveWave)."
}

New-Item -ItemType Directory -Force -Path $extDir | Out-Null
[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString() |
  Set-Content -NoNewline (Join-Path $extDir ".last_updated")

Write-Host "OK classes: $devDir"
Write-Host "Close/reopen MotiveWave, then set: Custom key + Ticker Auto + Conversion Auto + 0DTE"
