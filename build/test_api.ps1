# Teste l'API gexbot Classic /majors avec ta cle Custom.
# 1) Colle ta cle dans le fichier api_key.txt (une seule ligne)
# 2) Lance: powershell -ExecutionPolicy Bypass -File .\test_api.ps1

$ErrorActionPreference = "Stop"
$keyFile = Join-Path $PSScriptRoot "api_key.txt"
if (-not (Test-Path $keyFile)) {
  @"
# Colle ta cle Custom CI-DESSOUS (une ligne), puis relance ce script.
# Exemple: gexbot_custom_xxxxxxxx
"@ | Set-Content -Encoding UTF8 $keyFile
  Write-Host "Cree: $keyFile"
  Write-Host "Ouvre ce fichier, colle ta cle Custom, sauvegarde, relance ce script."
  exit 1
}

$key = (Get-Content $keyFile | Where-Object { $_ -and $_ -notmatch '^\s*#' } | Select-Object -First 1).Trim()
if (-not $key) { throw "api_key.txt est vide" }

$url = "https://api.gex.bot/v2/NQ_NDX/classic/gex_full/majors"
Write-Host "GET $url"
Write-Host "Key prefix: $($key.Substring(0, [Math]::Min(18, $key.Length)))..."

$args = @(
  "-sS", "-w", "`nHTTP:%{http_code}`n",
  "-H", "Authorization: Bearer $key",
  "-H", "User-Agent: MotiveWave-Diag/1.0",
  "-H", "Accept: application/json",
  $url
)
& curl.exe @args
