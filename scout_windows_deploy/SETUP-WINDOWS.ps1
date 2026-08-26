# Scout Crew + SSH bootstrap for Windows (run as Administrator in PowerShell)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Dest = Join-Path $env:USERPROFILE "scout_crew_config"
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
Copy-Item -Recurse -Force (Join-Path $Root "*") $Dest

Write-Host "== Enabling OpenSSH Server =="
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 -ErrorAction SilentlyContinue | Out-Null
Start-Service sshd
Set-Service -Name sshd -StartupType Automatic
New-NetFirewallRule -Name "OpenSSH-Server-In-TCP" -DisplayName "OpenSSH Server (sshd)" -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 -ErrorAction SilentlyContinue | Out-Null

$auth = Join-Path $env:USERPROFILE ".ssh\authorized_keys"
New-Item -ItemType Directory -Force -Path (Split-Path $auth) | Out-Null
$pub = Get-Content (Join-Path $Dest "ssh\id_ed25519_popos.pub") -Raw
if (-not (Test-Path $auth) -or -not (Select-String -Path $auth -Pattern "gibi@pop-os-tailscale" -Quiet)) {
  Add-Content -Path $auth -Value $pub.Trim()
}
# Admin authorized_keys path if elevated
$adminAuth = "C:\ProgramData\ssh\administrators_authorized_keys"
if (Test-Path "C:\ProgramData\ssh") {
  if (-not (Test-Path $adminAuth) -or -not (Select-String -Path $adminAuth -Pattern "gibi@pop-os-tailscale" -Quiet -ErrorAction SilentlyContinue)) {
    Add-Content -Path $adminAuth -Value $pub.Trim()
  }
  icacls $adminAuth /inheritance:r /grant "Administrators:F" /grant "SYSTEM:F" | Out-Null
}

Write-Host "== Tailscale SSH note =="
Write-Host "In Tailscale app: Preferences -> Enable Tailscale SSH (if available)"
Write-Host "Or: tailscale set --ssh=true"

Write-Host "== Scout config copied to $Dest =="
Write-Host "Install Ollama + Python, then clone https://github.com/wendigoro/scout_crew and copy config/* into the repo."
Write-Host "Done."
