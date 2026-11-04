# Builds a release APK of BoopBox (com.boopboxapp, same app as on Google Play) and signs it with the
# release key, for handing out directly. Google Play itself gets .\tools\release-aab.ps1. The key password comes
# from .\tools\save-key-password.ps1 if it was saved, else apksigner asks for it in this terminal; it is never
# passed on the command line.
#
#   .\tools\release.ps1                      -> BoopBox-release.apk in the project root
#   .\tools\release.ps1 -Keystore D:\keys\boopbox-release.jks
#
# Raise versionCode in app/build.gradle.kts before each release you hand out, or phones that
# already have BoopBox refuse the update.
param(
    [string]$Keystore = "$HOME\boopbox-release.jks",
    [string]$Alias = "boopbox"
)
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
# The SDK was installed from inside the Claude desktop app, whose AppData writes Windows redirects
# to its package folder; a normal terminal only sees it there. Newest build-tools with zipalign and apksigner.
$sdk = @("$env:ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk") +
    @(Get-ChildItem "$env:LOCALAPPDATA\Packages\Claude_*\LocalCache\Local\Android\Sdk" -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName) |
    Where-Object { $_ -and (Test-Path "$_\build-tools") } | Select-Object -First 1
if (-not $sdk) { throw "Android SDK not found (set ANDROID_HOME)" }
$buildTools = Get-ChildItem "$sdk\build-tools" -Directory |
    Where-Object { (Test-Path "$($_.FullName)\zipalign.exe") -and (Test-Path "$($_.FullName)\apksigner.bat") } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $buildTools) { throw "No Android build-tools with zipalign found under $sdk\build-tools" }
$apkDir = "$root\app\build\outputs\apk\release"
$aligned = "$apkDir\app-release-aligned.apk"
$out = "$root\BoopBox-release.apk"

if (-not (Test-Path $Keystore)) { throw "Release key not found: $Keystore" }

# Same setup as a debug build on this PC (see README, "Quirk on this PC").
$env:JAVA_HOME = "C:\Program Files\Java\jdk-22"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:/nonexistent_dir_for_uds_fallback"
Push-Location $root
try {
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
}

# 16 KB page alignment for the native libraries (needed on Android 15+ phones with 16 KB pages).
& "$buildTools\zipalign.exe" -f -P 16 4 "$apkDir\app-release-unsigned.apk" $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host ""
Write-Host "Signing with $Keystore (alias $Alias)."
. "$PSScriptRoot\key-password.ps1"
try {
    if (Use-SavedKeyPassword) {
        & "$buildTools\apksigner.bat" sign --ks $Keystore --ks-key-alias $Alias --ks-pass "env:$KeyPasswordEnv" --key-pass "env:$KeyPasswordEnv" --v4-signing-enabled false --out $out $aligned
    } else {
        & "$buildTools\apksigner.bat" sign --ks $Keystore --ks-key-alias $Alias --v4-signing-enabled false --out $out $aligned
    }
    if ($LASTEXITCODE -ne 0) { throw "Signing failed (wrong password?)" }
} finally {
    Clear-SavedKeyPassword
}
Remove-Item $aligned

$certs = & "$buildTools\apksigner.bat" verify --print-certs $out
if ($LASTEXITCODE -ne 0) { throw "Signature check failed" }
$sha = ($certs | Select-String "SHA-256 digest: ([0-9a-f]+)").Matches[0].Groups[1].Value.ToUpper()
$fingerprint = ($sha -split "(..)" | Where-Object { $_ }) -join ":"

Write-Host ""
Write-Host "Signed: $out"
Write-Host "Certificate SHA-256: $fingerprint"
Write-Host "This must match the upload key listed for com.boopboxapp in the Play Console."
