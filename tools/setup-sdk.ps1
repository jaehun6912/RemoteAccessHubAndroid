<#
.SYNOPSIS
  안드로이드 빌드 도구(JDK 17 + Android SDK)를 사용자 폴더에 설치한다. 관리자 권한이 필요 없다.
.DESCRIPTION
  설치 위치
    JDK 17       : %LOCALAPPDATA%\Android\jdk17
    Android SDK  : %LOCALAPPDATA%\Android\Sdk
  이미 있는 구성요소는 건너뛴다. -AcceptLicenses를 주면 sdkmanager의 Android SDK 라이선스에 동의한다.
.EXAMPLE
  .\setup-sdk.ps1 -AcceptLicenses               # 빌드 도구만
  .\setup-sdk.ps1 -AcceptLicenses -WithEmulator # + 에뮬레이터와 시스템 이미지, AVD(rah35)
#>
[CmdletBinding()]
param(
    [switch]$AcceptLicenses,
    [switch]$WithEmulator
)

# 네이티브 명령(curl, sdkmanager)의 stderr 출력을 오류로 보지 않도록 Continue로 두고 종료 코드·결과 폴더로 판단한다.
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$base = Join-Path $env:LOCALAPPDATA 'Android'
$jdk = Join-Path $base 'jdk17'
$sdk = Join-Path $base 'Sdk'
$tmp = Join-Path $base 'downloads'
New-Item -ItemType Directory -Force $base, $sdk, $tmp | Out-Null

function Get-File([string]$url, [string]$out) {
    if (Test-Path $out) { return }
    Write-Host "내려받는 중: $url"
    & curl.exe -fsSL --retry 3 -o "$out.part" $url
    if ($LASTEXITCODE -ne 0) { throw "내려받기 실패: $url" }
    Move-Item "$out.part" $out -Force
}

# --- JDK 17 (Eclipse Temurin)
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    $zip = Join-Path $tmp 'jdk17.zip'
    Get-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' $zip
    $x = Join-Path $tmp 'jdk17-x'
    if (Test-Path $x) { Remove-Item $x -Recurse -Force }
    New-Item -ItemType Directory $x | Out-Null
    & tar.exe -xf $zip -C $x
    $inner = Get-ChildItem $x -Directory | Select-Object -First 1
    Move-Item $inner.FullName $jdk
    Remove-Item $x -Recurse -Force
}
$env:JAVA_HOME = $jdk
Write-Host "JDK: $jdk"

# --- Android 명령줄 도구
$latest = Join-Path $sdk 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $latest 'bin\sdkmanager.bat'))) {
    $zip = Join-Path $tmp 'cmdline-tools.zip'
    Get-File 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' $zip
    $x = Join-Path $tmp 'cmdline-x'
    if (Test-Path $x) { Remove-Item $x -Recurse -Force }
    New-Item -ItemType Directory $x | Out-Null
    & tar.exe -xf $zip -C $x
    New-Item -ItemType Directory -Force (Split-Path $latest) | Out-Null
    Move-Item (Join-Path $x 'cmdline-tools') $latest
    Remove-Item $x -Recurse -Force
}
$sdkmanager = Join-Path $latest 'bin\sdkmanager.bat'
$avdmanager = Join-Path $latest 'bin\avdmanager.bat'

# sdkmanager는 질문마다 한 줄을 읽으므로 "y"를 한 줄에 하나씩 넉넉히 넘긴다.
# Windows PowerShell 5.1의 파이프는 .bat의 표준 입력으로 전달되지 않아 cmd의 입력 리다이렉트를 쓴다.
$yesFile = Join-Path $tmp 'yes.txt'
Set-Content -Path $yesFile -Value (1..60 | ForEach-Object { 'y' }) -Encoding ascii
function Invoke-Sdkmanager([string[]]$arguments) {
    $quoted = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    if ($AcceptLicenses) { & cmd.exe /c "`"$sdkmanager`" --sdk_root=`"$sdk`" $quoted < `"$yesFile`"" | Out-Null }
    else { & cmd.exe /c "`"$sdkmanager`" --sdk_root=`"$sdk`" $quoted" }
}
if ($AcceptLicenses) {
    Write-Host "Android SDK 라이선스 동의"
    Invoke-Sdkmanager @('--licenses')
}

$packages = @('platform-tools', 'platforms;android-35', 'build-tools;35.0.0')
$expected = @('platform-tools', 'platforms\android-35', 'build-tools\35.0.0')
if ($WithEmulator) {
    $packages += @('emulator', 'system-images;android-35;google_apis;x86_64')
    $expected += @('emulator', 'system-images\android-35\google_apis\x86_64')
}
Write-Host "설치: $($packages -join ', ')"
Invoke-Sdkmanager $packages
foreach ($p in $expected) {
    if (-not (Test-Path (Join-Path $sdk $p))) { throw "설치되지 않음: $p (라이선스 동의 여부를 확인하세요)" }
}

if ($WithEmulator) {
    $avdHome = Join-Path $env:USERPROFILE '.android\avd'
    if (-not (Test-Path (Join-Path $avdHome 'rah35.avd'))) {
        Write-Host "AVD 만들기: rah35"
        'no' | & $avdmanager create avd -n rah35 -k 'system-images;android-35;google_apis;x86_64' -d pixel_6 --force
    }
}

Write-Host ""
Write-Host "완료. 빌드할 때 다음 환경 변수를 씁니다(build.ps1이 자동으로 설정):"
Write-Host "  JAVA_HOME=$jdk"
Write-Host "  ANDROID_HOME=$sdk"
