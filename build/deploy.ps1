# Compile and deploy custom MotiveWave studies/strategies
# - gexbot  -> gexbot_majors.jar
# - qulla   -> qulla_nq.jar
# - emavwap -> ema_vwap_cross.jar
# - trendtarget -> ttr_alma.jar
# Always refreshes Extensions/dev + .last_updated.
# Replaces jars when unlocked; if locked, stages *.jar.new — never kills MotiveWave.

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
  Where-Object { $_.FullName -match '[\\/](gexbot|qulla|emavwap|trendtarget)[\\/]' } |
  ForEach-Object { $_.FullName })

if ($sources.Count -lt 1) { throw "No gexbot/qulla/emavwap/trendtarget java sources found" }
Write-Host "Compiling $($sources.Count) files..."

# mwave_sdk.jar is Java 26 bytecode; compile with matching release.
& javac -encoding UTF-8 -g --release 26 -cp $sdk -d $classesDir @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }

# Hot-reload payload: always overwrite Extensions/dev
Remove-Item -Recurse -Force $devDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $devDir | Out-Null
Copy-Item -Recurse -Force (Join-Path $classesDir "*") $devDir

function Deploy-Jar([string]$packageDir, [string]$jarName) {
  $srcPkg = Join-Path $classesDir $packageDir
  if (-not (Test-Path $srcPkg)) {
    Write-Host "SKIP jar $jarName (no package $packageDir)"
    return $false
  }

  $jarPath = Join-Path $extDir $jarName
  $tmpJar = Join-Path $extDir ($jarName + ".tmp")
  $stagedJar = Join-Path $extDir ($jarName + ".new")

  if (Test-Path $tmpJar) { Remove-Item $tmpJar -Force -ErrorAction SilentlyContinue }
  & jar cf $tmpJar -C $classesDir $packageDir
  if ($LASTEXITCODE -ne 0) { throw "jar creation failed for $jarName" }

  try {
    if (Test-Path $jarPath) { Remove-Item $jarPath -Force -ErrorAction Stop }
    Move-Item -Force $tmpJar $jarPath
    if (Test-Path $stagedJar) { Remove-Item $stagedJar -Force -ErrorAction SilentlyContinue }
    Write-Host "OK jar replaced: $jarPath"
    return $true
  }
  catch {
    if (Test-Path $stagedJar) { Remove-Item $stagedJar -Force -ErrorAction SilentlyContinue }
    Move-Item -Force $tmpJar $stagedJar
    Write-Host "WARN $jarName locked - wrote $jarName.new instead (MotiveWave left running)."
    Write-Host "Hot-reload: Extensions/dev updated. Jar will promote on next unlock/redeploy."
    return $false
  }
}

function Promote-StagedJars {
  Get-ChildItem $extDir -Filter "*.jar.new" -ErrorAction SilentlyContinue | ForEach-Object {
    # qulla_nq.jar.new -> BaseName = qulla_nq.jar
    $target = Join-Path $extDir $_.BaseName
    try {
      if (Test-Path $target) { Remove-Item $target -Force -ErrorAction Stop }
      Move-Item -Force $_.FullName $target
      Write-Host "OK promoted staged jar to $(Split-Path $target -Leaf)"
    }
    catch {
      Write-Host "WARN still locked, kept $($_.Name)"
    }
  }
}

New-Item -ItemType Directory -Force -Path $extDir | Out-Null
Promote-StagedJars

foreach ($old in @("gexbot_custom_majors.jar", "gexbot_custom_majors_v2.jar")) {
  Remove-Item (Join-Path $extDir $old) -Force -ErrorAction SilentlyContinue
}

$okGex = Deploy-Jar "gexbot" "gexbot_majors.jar"
# Also stage analysis/study aliases MW may already have loaded (often locked)
foreach ($alias in @("gexbot_study.jar", "gexbot_majors_analysis.jar")) {
  Deploy-Jar "gexbot" $alias | Out-Null
}
# Always write a fresh unlocked jar so a reload can pick up the fix without unlock
$freshName = "gexbot_fix.jar"
try {
  & jar cf (Join-Path $extDir $freshName) -C $classesDir gexbot
  Write-Host "OK fresh jar: $freshName"
} catch {
  Write-Host "WARN could not write $freshName"
}
$okQulla = Deploy-Jar "qulla" "qulla_nq.jar"
$okEma = Deploy-Jar "emavwap" "ema_vwap_cross.jar"
$okTtr = Deploy-Jar "trendtarget" "ttr_alma.jar"
# Never kill MotiveWave. Locked jars stay as *.jar.new; Extensions/dev is always refreshed.

[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString() |
  Set-Content -NoNewline (Join-Path $extDir ".last_updated")

$statusPath = Join-Path $extDir "custom_deploy_status.txt"
$stamp = @(
  "deployed_utc=$([DateTimeOffset]::UtcNow.ToString('o'))"
  "gexbot_jar_ok=$okGex"
  "qulla_jar_ok=$okQulla"
  "emavwap_jar_ok=$okEma"
  "trendtarget_jar_ok=$okTtr"
) -join "`n"
Set-Content -Path $statusPath -Value $stamp

Write-Host "OK classes: $devDir"
Write-Host "OK .last_updated refreshed"
if ($okGex -and $okQulla -and $okEma -and $okTtr) {
  Write-Host "Jars CURRENT. Hot-reload via Extensions/dev; remove/re-add study if needed."
}
else {
  Write-Host "Some jars locked (*.jar.new staged). MotiveWave left running - Extensions/dev updated for hot-reload."
  Write-Host "Jars will promote on next deploy once MW unlocks them (no auto-close)."
}
