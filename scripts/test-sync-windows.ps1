# 使用两个真实 Qt 客户端目录与独立 Go 进程验证同步；不会写入日常应用数据或线上数据库。
# 测试设备密钥只保存在隔离目录中，不输出到控制台或作为进程参数传递。
param([string]$QtDirectory = $env:LYUSHI_QT_DIR, [string]$ToolchainDirectory = $env:LYUSHI_TOOLCHAIN_DIR)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$sandbox = Join-Path $projectDirectory ('build/sync-local-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $sandbox | Out-Null
# 测试仅使用参数提供的工具目录，不依赖开发者本机盘符。
if (-not $QtDirectory) { $QtDirectory = Join-Path $projectDirectory ".tools/qt/ucrt64" }
if (-not $ToolchainDirectory) { $ToolchainDirectory = $QtDirectory }
$env:PATH = "$QtDirectory/bin;$ToolchainDirectory/bin;$env:PATH"
$env:QT_PLUGIN_PATH = "$QtDirectory/share/qt6/plugins"
$env:QT_QPA_PLATFORM = 'offscreen'
$client = Join-Path $projectDirectory 'build/desktop-windows/lyushi.exe'
$server = Join-Path $projectDirectory 'build/lyushi-server.exe'
$database = Join-Path $sandbox 'server.sqlite'
$keyFile = Join-Path $sandbox 'test-device.key'
& $server --db $database --create-device test-device --key-file $keyFile | Out-Null
if ($LASTEXITCODE -ne 0) { throw '创建隔离设备失败' }
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
$listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
$address = "http://127.0.0.1:$port"
$process = Start-Process -FilePath $server -ArgumentList '--db',$database,'--listen',"127.0.0.1:$port" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$sandbox/server.log" -RedirectStandardError "$sandbox/server-error.log"
function Invoke-Client([string]$mode,[string]$directory,[string[]]$extra=@()) {
    $arguments = @($mode,$directory) + $extra
    $job = Start-Process -FilePath $client -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput "$sandbox/client.log" -RedirectStandardError "$sandbox/client-error.log"
    if (-not $job.WaitForExit(60000)) { $job.Kill(); throw "客户端超时：$mode" }
    if ($job.ExitCode -ne 0) { throw "客户端失败：$mode；日志见隔离目录" }
}
function Inspect-Client([string]$directory) {
    $path = Join-Path $sandbox 'inspect.json'; Invoke-Client '--inspect' $directory @($path)
    Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
}
function Apply-Edit([string]$directory,$record,[string]$task) {
    $path = Join-Path $sandbox 'command.json'
    @{action='edit';id=$record.id;ledgerId=$record.ledgerId;task=$task;started=$record.started;ended=$record.ended} | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
    Invoke-Client '--command' $directory @($path)
}
try {
    $ready = $false
    for ($attempt=0;$attempt -lt 40;$attempt++) {
        try { if ((Invoke-RestMethod "$address/health" -TimeoutSec 1).status -eq 'ok') { $ready=$true; break } } catch { Start-Sleep -Milliseconds 100 }
    }
    if (-not $ready) { throw '测试服务器未启动' }
    $one=Join-Path $sandbox 'one'; $two=Join-Path $sandbox 'two'
    Invoke-Client '--seed-active' $one
    Invoke-Client '--sync-once' $one @($address,$keyFile)
    Invoke-Client '--sync-once' $two @($address,$keyFile)
    $second=Inspect-Client $two
    if (@($second.records).Count -ne 1 -or $null -ne $second.records[0].ended) { throw '另一客户端未收到进行中状态' }
    Invoke-Client '--resume-active' $two
    Invoke-Client '--sync-once' $two @($address,$keyFile)
    Invoke-Client '--sync-once' $one @($address,$keyFile)
    $first=Inspect-Client $one
    if ($null -eq $first.records[0].ended) { throw '跨设备结束未同步' }
    Apply-Edit $one $first.records[0] 'offline-A'
    Apply-Edit $two $first.records[0] 'offline-B'
    Invoke-Client '--sync-once' $one @($address,$keyFile)
    Invoke-Client '--sync-once' $two @($address,$keyFile)
    Invoke-Client '--sync-once' $one @($address,$keyFile)
    $first=Inspect-Client $one
    $tasks=@($first.conflicts | ForEach-Object { $_.entity.task })
    if ('offline-A' -notin $tasks -or 'offline-B' -notin $tasks) { throw '并发内容没有完整保留' }

    # 超过一页的旧版本先于本机已确认修改返回时，本机内容必须等下载追上后再合并。
    $token=[System.IO.File]::ReadAllText($keyFile).Trim()
    $base=$first.records[0]; $parent=$base.version; $serverId=''; $cursor=0
    for ($batch=0;$batch -lt 6;$batch++) {
        $operations=@()
        for ($item=0;$item -lt 50;$item++) {
            $version=[guid]::NewGuid().ToString(); $entity=@{id=$base.id;version=$version;ledgerId=$base.ledgerId;task="server-$batch-$item";started=$base.started;ended=$base.ended;deleted=$false;revision=1}
            $operations+=@{id=$version;kind='record';entityId=$base.id;parents=@($parent);entity=$entity}; $parent=$version
        }
        $body=@{serverId=$serverId;cursor=$cursor;operations=$operations}|ConvertTo-Json -Depth 9 -Compress
        $reply=Invoke-RestMethod "$address/v1/sync" -Method Post -Headers @{Authorization="Bearer $token"} -ContentType application/json -Body $body
        $serverId=$reply.serverId; $cursor=$reply.cursor
    }
    Apply-Edit $one $base 'pending-across-pages'
    Invoke-Client '--sync-once' $one @($address,$keyFile)
    $final=Inspect-Client $one
    if ($final.records[0].task -ne 'pending-across-pages' -or $final.pending -ne 0) { throw '分页覆盖了本机新内容' }

    # 明确删除覆盖全部已知分支，仍保留冲突档案；另一端收到删除状态。
    Invoke-Client '--sync-once' $two @($address,$keyFile)
    $delete=Join-Path $sandbox 'delete.json'; @{action='delete';id=$base.id}|ConvertTo-Json|Set-Content -LiteralPath $delete -Encoding UTF8
    Invoke-Client '--command' $one @($delete); Invoke-Client '--sync-once' $one @($address,$keyFile); Invoke-Client '--sync-once' $two @($address,$keyFile)
    if (-not (Inspect-Client $two).records[0].deleted) { throw '删除未传播' }
    $summary=@{passed=$true;crossDeviceStop=$true;offlineBranches=$true;paginationProtectsPending=$true;deletePropagation=$true;directory=$sandbox}
    $summary|ConvertTo-Json|Set-Content -LiteralPath "$projectDirectory/build/sync-local-result.json" -Encoding UTF8
    $summary|ConvertTo-Json
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id }
}
