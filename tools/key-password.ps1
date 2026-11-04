# Shared by release.ps1 and release-aab.ps1 (dot-sourced): the release key's password, saved once with
# .\tools\save-key-password.ps1. It is stored encrypted with Windows DPAPI, so only this Windows account on
# this PC can read it back. It is handed to the signing tools through an environment variable (never on a
# command line) and removed again after signing.

$KeyPasswordFile = "$HOME\.secrets\boopbox-release.pwd"
$KeyPasswordEnv = "BOOPBOX_KEY_PASSWORD"

# True when a saved password was found and put in $env:BOOPBOX_KEY_PASSWORD; false means "ask in the terminal".
function Use-SavedKeyPassword {
    if (-not (Test-Path $KeyPasswordFile)) { return $false }
    try {
        $secure = (Get-Content $KeyPasswordFile -Raw).Trim() | ConvertTo-SecureString
        Set-Item "Env:$KeyPasswordEnv" ([System.Net.NetworkCredential]::new("", $secure).Password)
        return $true
    } catch {
        Write-Warning "Saved key password could not be read ($KeyPasswordFile); asking instead."
        return $false
    }
}

function Clear-SavedKeyPassword {
    Remove-Item "Env:$KeyPasswordEnv" -ErrorAction SilentlyContinue
}
