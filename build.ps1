<#
.SYNOPSIS
  RemoteAccessHub 안드로이드 빌드/검사 스크립트.
.DESCRIPTION
  JDK 17과 Android SDK는 tools\setup-sdk.ps1로 사용자 폴더(%LOCALAPPDATA%\Android)에 설치한 것을 쓴다.
  JAVA_HOME / ANDROID_HOME이 이미 있으면 그쪽을 쓴다.
.EXAMPLE
  .\build.ps1                 # 디버그 APK 빌드 + 단위 검사
  .\build.ps1 -Release        # + 배포용 APK(app\build\outputs\apk\release), artifacts에 복사
  .\build.ps1 -SelfTest       # + 연결된 기기/에뮬레이터에서 모의 공유기 자체검사(계측 검사)
  .\build.ps1 -SkipTests
#>
[CmdletBinding()]
param(
    [switch]$Release,
    [switch]$SelfTest,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not $env:JAVA_HOME) {
    $jdk = Join-Path $env:LOCALAPPDATA 'Android\jdk17'
    if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) { throw "JDK 17을 찾을 수 없습니다. tools\setup-sdk.ps1 -AcceptLicenses 로 설치하세요." }
    $env:JAVA_HOME = $jdk
}
if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (-not (Test-Path (Join-Path $sdk 'platforms'))) { throw "Android SDK를 찾을 수 없습니다. tools\setup-sdk.ps1 -AcceptLicenses 로 설치하세요." }
    $env:ANDROID_HOME = $sdk
}
Write-Host "JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Cyan
Write-Host "ANDROID_HOME=$env:ANDROID_HOME" -ForegroundColor Cyan

function Invoke-Gradle([string[]]$tasks) {
    & .\gradlew.bat --no-daemon @tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle 실패: $($tasks -join ' ')" }
}

$tasks = @(':app:assembleDebug')
if (-not $SkipTests) { $tasks += ':app:testDebugUnitTest' }
if ($Release) { $tasks += ':app:assembleRelease' }
Invoke-Gradle $tasks

if ($SelfTest) {
    Write-Host "`n== 모의 공유기 자체검사(계측 검사) ==" -ForegroundColor Cyan
    Invoke-Gradle @(':app:connectedDebugAndroidTest')
}

if ($Release) {
    $out = Join-Path $root 'artifacts'
    New-Item -ItemType Directory -Force $out | Out-Null
    $version = (Select-String -Path 'app\build.gradle.kts' -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
    $apk = Get-ChildItem 'app\build\outputs\apk\release\*.apk' | Select-Object -First 1
    $dest = Join-Path $out "RemoteAccessHub-android-$version.apk"
    Copy-Item $apk.FullName $dest -Force
    Write-Host "배포용 APK: $dest" -ForegroundColor Green
}
