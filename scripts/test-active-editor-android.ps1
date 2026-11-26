# 在专用测试设备上验证真实 Activity 旋转、过期草稿、保存、取消及完整退出。
# 调试入口使用独立数据库与空白偏好，不连接用户服务器或修改正式账户。
param([string]$Serial = 'emulator-5556', [string]$AndroidSdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) { throw '请设置 ANDROID_HOME 或传入 -AndroidSdk' }
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$package = 'dev.lyushi.app'
$reportFile = 'files/active-editor-ui-result.json'
function Run-Phase([string]$phase) {
    & $adb -s $Serial shell am force-stop $package
    & $adb -s $Serial shell run-as $package rm -f $reportFile
    & $adb -s $Serial shell am start -W -n "$package/.MainActivity" --es test_action $phase | Out-Host
    if ($LASTEXITCODE -ne 0) { throw '启动安卓表单验收失败' }
    $report = $null
    for ($attempt=0; $attempt -lt 90; $attempt++) {
        $text = (& $adb -s $Serial shell run-as $package cat $reportFile 2>$null) -join "`n"
        if ($LASTEXITCODE -eq 0 -and $text) {
            try { $report = $text | ConvertFrom-Json } catch { $report = $null }
            if ($report -and $report.phase -eq $phase) { break }
            if ($report -and -not $report.passed) { throw "安卓表单验收失败：$($report.error)" }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $report -or -not $report.passed -or $report.phase -ne $phase) { throw '等待安卓表单验收报告超时' }
    return $report
}
if ((& $adb -s $Serial get-state 2>$null) -ne 'device') { throw '测试设备未连接' }
& $adb -s $Serial install -r (Join-Path $projectDirectory 'apps/android/app/build/outputs/apk/debug/app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw '安装调试 APK 失败' }
$rotation = Run-Phase 'active-editor-ui-test'
$reopen = Run-Phase 'active-editor-reopen-test'
$result = @{passed=$true;rotation=$rotation;reopen=$reopen}
New-Item -ItemType Directory -Path (Join-Path $projectDirectory 'build') -Force | Out-Null
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $projectDirectory 'build/android-active-editor-result.json') -Encoding UTF8
$result | ConvertTo-Json -Depth 12
