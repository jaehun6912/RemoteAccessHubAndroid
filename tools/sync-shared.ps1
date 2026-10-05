<#
.SYNOPSIS
  Windows 버전 저장소(RemoteAccessHub)와 같이 쓰는 파일을 이 저장소로 복사한다.
.DESCRIPTION
  화면 판독 스크립트와 모의 공유기 화면은 Windows 버전에서 실기기 진단으로 다듬어 온 파일이다.
  두 버전이 같은 규칙으로 공유기 화면을 읽도록, Windows 쪽에서 고치면 이 스크립트로 가져온다.
    src/RemoteAccessHub/Router/Scripts/probe.js  → app/src/main/assets/probe.js
    src/RemoteAccessHub/SelfTest/Mock/index.html → app/src/debug/assets/index.html (디버그 빌드에만 들어감)
.EXAMPLE
  .\tools\sync-shared.ps1                                  # 형제 폴더 ..\RemoteAccessHub 에서
  .\tools\sync-shared.ps1 -WindowsRepo D:\src\RemoteAccessHub
#>
[CmdletBinding()]
param(
    [string]$WindowsRepo
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $WindowsRepo) { $WindowsRepo = Join-Path (Split-Path -Parent $root) 'RemoteAccessHub' }
if (-not (Test-Path (Join-Path $WindowsRepo 'RemoteAccessHub.sln'))) { throw "Windows 버전 저장소를 찾을 수 없습니다: $WindowsRepo" }

$files = @(
    @{ From = 'src\RemoteAccessHub\Router\Scripts\probe.js'; To = 'app\src\main\assets\probe.js' },
    @{ From = 'src\RemoteAccessHub\SelfTest\Mock\index.html'; To = 'app\src\debug\assets\index.html' }
)
foreach ($f in $files) {
    $src = Join-Path $WindowsRepo $f.From
    $dst = Join-Path $root $f.To
    New-Item -ItemType Directory -Force (Split-Path -Parent $dst) | Out-Null
    $same = (Test-Path $dst) -and ((Get-FileHash $src).Hash -eq (Get-FileHash $dst).Hash)
    Copy-Item $src $dst -Force
    Write-Host ("{0} {1}" -f ($(if ($same) { '같음  ' } else { '갱신됨' })), $f.To)
}
