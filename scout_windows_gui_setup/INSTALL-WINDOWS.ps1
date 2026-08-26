#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $env:USERPROFILE "scout_setup"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Log = Join-Path $LogDir "install.log"
function Log($m){ $l="[{0}] {1}" -f (Get-Date -Format o), $m; Add-Content $Log $l; Write-Host $l }

Log "=== Scout Windows setup (Hermes-hc + nvm/npm + GUI notes) ==="
Copy-Item -Recurse -Force (Join-Path $Root "*") (Join-Path $env:USERPROFILE "scout_crew_bundle")

# Ollama
function Ensure-Ollama {
  if (Get-Command ollama -ErrorAction SilentlyContinue) { return }
  foreach ($c in @("$env:LOCALAPPDATA\Programs\Ollama\ollama.exe","$env:ProgramFiles\Ollama\ollama.exe")) {
    if (Test-Path $c) { $env:Path = "$(Split-Path $c);" + $env:Path; return }
  }
  $installer = Join-Path $env:TEMP "OllamaSetup.exe"
  Invoke-WebRequest "https://ollama.com/download/OllamaSetup.exe" -OutFile $installer -UseBasicParsing
  Start-Process $installer -ArgumentList "/S" -Wait -ErrorAction SilentlyContinue
  Start-Sleep 3
  $env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")
}
Ensure-Ollama
Log "ollama $(ollama --version 2>&1)"
try { Start-Process ollama -ArgumentList "serve" -WindowStyle Hidden -ErrorAction SilentlyContinue } catch {}
Start-Sleep 2
Log "pull llama3.1 + qwen3:8b"
ollama pull llama3.1
ollama pull qwen3:8b
$mf100 = Join-Path $Root "modelfiles\Modelfile.scout-hermes-hc1.0.0"
$mf64  = Join-Path $Root "modelfiles\Modelfile.scout-hermes-hc1.0.0-64k"
if (Test-Path $mf100) { ollama create scout-hermes-hc1.0.0 -f $mf100 }
if (Test-Path $mf64)  { ollama create scout-hermes-hc1.0.0-64k -f $mf64 }
ollama list | Out-File (Join-Path $LogDir "ollama-list.txt")

# nvm + node LTS
function Ensure-Nvm {
  $env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")
  if (-not (Get-Command nvm -ErrorAction SilentlyContinue) -and -not (Test-Path "$env:ProgramFiles\nvm\nvm.exe")) {
    $rel = Invoke-RestMethod "https://api.github.com/repos/coreybutler/nvm-windows/releases/latest" -Headers @{"User-Agent"="scout"}
    $asset = $rel.assets | Where-Object { $_.name -match 'nvm-setup\.exe$' } | Select-Object -First 1
    $setup = Join-Path $env:TEMP $asset.name
    Invoke-WebRequest $asset.browser_download_url -OutFile $setup -UseBasicParsing
    Start-Process $setup -Wait
    $env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")
  }
  $nvm = (Get-Command nvm -ErrorAction SilentlyContinue).Source
  if (-not $nvm -and (Test-Path "$env:ProgramFiles\nvm\nvm.exe")) { $nvm = "$env:ProgramFiles\nvm\nvm.exe" }
  if ($nvm) {
    & $nvm install lts
    & $nvm use lts
  }
  $env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")
  Log "node=$(node -v 2>&1) npm=$(npm -v 2>&1)"
}
Ensure-Nvm

# OpenSSH
try {
  Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 -ErrorAction SilentlyContinue | Out-Null
  Start-Service sshd -ErrorAction SilentlyContinue
  Set-Service sshd -StartupType Automatic -ErrorAction SilentlyContinue
  New-NetFirewallRule -Name "OpenSSH-Server-In-TCP" -DisplayName "OpenSSH Server (sshd)" -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 -ErrorAction SilentlyContinue | Out-Null
} catch { Log "sshd optional: $_" }

# GUI note
$guiNote = @"
Scout full desktop GUI (PySide6) is best on Linux/WSL with a display.
On native Windows:
  1) Install WSL2 Ubuntu
  2) Clone https://github.com/wendigoro/scout_crew
  3) crewai install && scout-gui
  4) Hermes tab opens at startup; Blackboard + Pipeline monitors included
  5) Classic Hermes: hermes desktop
Bundle copied to %USERPROFILE%\scout_crew_bundle
Models: scout-hermes-hc1.0.0 (100k thinking) + 64k floor
"@
$guiNote | Out-File (Join-Path $LogDir "GUI-NOTES.txt") -Encoding utf8
Log "DONE. See $LogDir"
Write-Host $guiNote
