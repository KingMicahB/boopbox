# Saves the release key's password once, so release.ps1 and release-aab.ps1 stop asking for it.
# Type it here, in this terminal; it is checked against the key, then stored encrypted with Windows DPAPI
# in %USERPROFILE%\.secrets\boopbox-release.pwd (readable only by this Windows account on this PC).
# Anything running as you can still decrypt it, so keep the password in your password manager too.
#
#   .\tools\save-key-password.ps1             -> save (or replace) the password
#   .\tools\save-key-password.ps1 -Forget     -> delete the saved password; the scripts ask again
param(
    [string]$Keystore = "$HOME\boopbox-release.jks",
    [string]$Alias = "boopbox",
    [switch]$Forget
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\key-password.ps1"

if ($Forget) {
    Remove-Item $KeyPasswordFile -ErrorAction SilentlyContinue
    Write-Host "Saved key password removed. The release scripts will ask for it again."
    return
}

if (-not (Test-Path $Keystore)) { throw "Release key not found: $Keystore" }
$jdk = "C:\Program Files\Java\jdk-22"

$secure = Read-Host "Password for $Keystore" -AsSecureString
Set-Item "Env:$KeyPasswordEnv" ([System.Net.NetworkCredential]::new("", $secure).Password)
try {
    # Proves the password opens the key before saving it.
    & "$jdk\bin\keytool.exe" -list -keystore $Keystore -alias $Alias -storepass:env $KeyPasswordEnv | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "That password doesn't open $Keystore (alias $Alias). Nothing saved." }
} finally {
    Clear-SavedKeyPassword
}

New-Item -ItemType Directory -Force (Split-Path $KeyPasswordFile) | Out-Null
$secure | ConvertFrom-SecureString | Set-Content -Path $KeyPasswordFile -Encoding ascii
Write-Host "Saved (encrypted for this Windows account) to $KeyPasswordFile."
Write-Host "release.ps1 and release-aab.ps1 will sign without asking. Undo with: .\tools\save-key-password.ps1 -Forget"
