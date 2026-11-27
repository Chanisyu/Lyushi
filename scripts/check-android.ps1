# 使用独立测试数据库运行 Android 存储协议检查，不读取设备密钥或连接真实服务器。
param([string]$Serial = 'emulator-5556', [string]$AndroidSdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
# SDK 路径由参数或环境变量提供。
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) { throw '请设置 ANDROID_HOME 或传入 -AndroidSdk' }
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
New-Item -ItemType Directory -Path (Join-Path $projectDirectory 'build') -Force | Out-Null
$package = 'dev.lyushi.app'
$apk = Join-Path $projectDirectory 'apps/android/app/build/outputs/apk/debug/app-debug.apk'
$reportPath = Join-Path $projectDirectory 'build/android-sync-store-result.json'
if ((& $adb -s $Serial get-state 2>$null) -ne 'device') { throw '测试设备未连接' }
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw '安装测试 APK 失败' }
& $adb -s $Serial shell am force-stop $package
& $adb -s $Serial shell run-as $package rm -f files/sync-store-result.json
if (Test-Path -LiteralPath $reportPath) { Move-Item -LiteralPath $reportPath -Destination "$reportPath.previous" -Force }
& $adb -s $Serial shell am start -W -n "$package/.MainActivity" --es test_action sync-store-test
'正在等待独立存储检查报告。'
$report = $null
for ($attempt = 0; $attempt -lt 90; $attempt++) {
    $text = (& $adb -s $Serial shell run-as $package cat files/sync-store-result.json 2>$null) -join "`n"
    if ($LASTEXITCODE -eq 0 -and $text) {
        try { $report = $text | ConvertFrom-Json } catch { $report = $null }
        if ($report) { break }
    }
    Start-Sleep -Milliseconds 500
}
if (-not $report) { throw '等待安卓检查报告超时，未写入成功结果' }
[System.IO.File]::WriteAllText($reportPath,$text,[System.Text.UTF8Encoding]::new($false))
# 调试构建只用于验收。正式安装包由独立的发布签名步骤生成。
if (-not $report.passed -or $report.appVersion -ne '1.0.0' -or $report.versionCode -ne 11) { throw 'Android 正式版源码的存储检查未通过' }
$report | ConvertTo-Json -Depth 4
