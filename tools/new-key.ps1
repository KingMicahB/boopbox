# Creates a new BoopBox release key at %USERPROFILE%\boopbox-release.jks (alias boopbox), the
# path release.ps1 and release-aab.ps1 use. keytool asks for the new password in this terminal;
# the password is never written to disk or passed on the command line. Save it in a password
# manager: without it no update can ship signed with this key.
#
# An existing key at that path is moved aside to boopbox-release-old-<date>.jks, never deleted.
#
#   .\tools\new-key.ps1
param(
    [string]$Keystore = "$HOME\boopbox-release.jks",
    [string]$Alias = "boopbox"
)
$ErrorActionPreference = "Stop"

$jdk = "C:\Program Files\Java\jdk-22"

if (Test-Path $Keystore) {
    $old = Join-Path (Split-Path $Keystore) ("boopbox-release-old-" + (Get-Date -Format "yyyy-MM-dd") + ".jks")
    Move-Item $Keystore $old
    Write-Host "Old key moved to $old"
}

Write-Host "Creating $Keystore (alias $Alias). Choose a password and save it in your password manager."
& "$jdk\bin\keytool.exe" -genkeypair -keystore $Keystore -storetype PKCS12 -alias $Alias `
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=BoopBox"
if ($LASTEXITCODE -ne 0) { throw "keytool failed" }

Write-Host ""
Write-Host "Created $Keystore. Back it up (with its password) somewhere other than this PC."
Write-Host "Next: .\tools\release.ps1, then register the SHA-256 it prints as the upload key for com.boopboxapp in the Play Console."
