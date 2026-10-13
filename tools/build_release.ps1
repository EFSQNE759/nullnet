param(
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [string]$Notes = "",
    [string]$UpdateUrl = "https://github.com/EFSQNE759/nullnet/releases/latest/download/app-release.apk"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path | Split-Path -Parent

Push-Location $root
try {
    Write-Host "==> Release APK derleniyor (v$VersionName, kod $VersionCode)..."
    & .\gradlew.bat --no-daemon assembleRelease `
        "-PversionCode=$VersionCode" "-PversionName=$VersionName"
    if ($LASTEXITCODE -ne 0) { throw "Derleme basarisiz." }

    Write-Host "==> Imzali update.json uretiliyor..."
    & python tools\make_update.py `
        --apk app\build\outputs\apk\release\app-release.apk `
        --url $UpdateUrl `
        --version-code $VersionCode `
        --version-name $VersionName `
        --notes $Notes `
        --out update.json
    if ($LASTEXITCODE -ne 0) { throw "update.json uretilemedi." }

    Write-Host ""
    Write-Host "==> HAZIR ================================"
    Write-Host "APK      : $root\app\build\outputs\apk\release\app-release.apk"
    Write-Host "update.json: $root\update.json"
    Write-Host "Not: update.json'i GitHub Release'e dosya olarak ekle."
} finally {
    Pop-Location
}