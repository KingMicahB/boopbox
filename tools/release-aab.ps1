# Builds a release App Bundle (.aab) of BoopBox (com.boopboxapp) for Google Play (internal testing
# track and up). Signs it with the release key, which acts as the Play upload key. jarsigner asks for the
# key password in this terminal; the password is never written to disk or passed on the command line.
#
#   .\tools\release-aab.ps1                      -> BoopBox-release.aab in the project root
#   .\tools\release-aab.ps1 -Keystore D:\keys\boopbox-release.jks
#
# Raise versionCode in app/build.gradle.kts before each upload: Play rejects a versionCode it
# has already seen, on any track.
param(
    [string]$Keystore = "$HOME\boopbox-release.jks",
    [string]$Alias = "boopbox"
)
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$jdk = "C:\Program Files\Java\jdk-22"
$bundle = "$root\app\build\outputs\bundle\release\app-release.aab"
$out = "$root\BoopBox-release.aab"

if (-not (Test-Path $Keystore)) { throw "Release key not found: $Keystore" }

# The SDK was installed from inside the Claude desktop app, whose AppData writes Windows redirects to its
# package folder; a normal terminal only sees it there, so local.properties' sdk.dir misses it. Same search as release.ps1.
$sdk = @("$env:ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk") +
    @(Get-ChildItem "$env:LOCALAPPDATA\Packages\Claude_*\LocalCache\Local\Android\Sdk" -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName) |
    Where-Object { $_ -and (Test-Path "$_\build-tools") } | Select-Object -First 1
if (-not $sdk) { throw "Android SDK not found (set ANDROID_HOME)" }
$env:ANDROID_HOME = $sdk

# Same setup as a debug build on this PC (see README, "Quirk on this PC").
$env:JAVA_HOME = $jdk
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:/nonexistent_dir_for_uds_fallback"
Push-Location $root
try {
    & .\gradlew.bat bundleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Signing with $Keystore (alias $Alias)."
& "$jdk\bin\jarsigner.exe" -keystore $Keystore -sigalg SHA256withRSA -digestalg SHA-256 -signedjar $out $bundle $Alias
if ($LASTEXITCODE -ne 0) { throw "Signing failed (wrong password?)" }

$certs = & "$jdk\bin\keytool.exe" -printcert -jarfile $out
if ($LASTEXITCODE -ne 0) { throw "Signature check failed" }
$fingerprint = ($certs | Select-String "SHA256: ([0-9A-F:]+)").Matches[0].Groups[1].Value

Write-Host ""
Write-Host "Signed: $out"
Write-Host "Upload certificate SHA-256: $fingerprint"
Write-Host "Upload it in Play Console > Test and release > Testing > Internal testing > Create new release."
