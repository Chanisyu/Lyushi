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
& $server --db $database --init-admin --password-file (Join-Path $sandbox 'admin-initial.txt') | Out-Null
if ($LASTEXITCODE -ne 0) { throw '初始化隔离管理员失败' }
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
function Apply-ActiveEdit([string]$directory,$record,[string]$task) {
    $path = Join-Path $sandbox 'active-command.json'
    @{action='edit-active';id=$record.id;expectedVersion=$record.version;task=$task;tag=''} | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
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
    # 活动改名不改变记录编号或开始边界；对端接收后继续同一个任务。
    Apply-ActiveEdit $one $second.records[0] '专注更正'
    Invoke-Client '--sync-once' $one @($address,$keyFile); Invoke-Client '--sync-once' $two @($address,$keyFile)
    $renamed=(Inspect-Client $two).records[0]
    if ($renamed.task -ne '专注更正' -or $renamed.id -ne $second.records[0].id -or $renamed.started -ne $second.records[0].started -or $null -ne $renamed.ended) { throw '活动改名改变任务身份或时间' }
    # 原任务在设备一分心、设备二恢复，所有状态切换共用同一记录编号。
    $focusCommand = Join-Path $sandbox 'focus-command.json'
    $start = $second.records[0].started
    @{action='distract';task='点饭';now=($start+1000)} | ConvertTo-Json | Set-Content -LiteralPath $focusCommand -Encoding UTF8
    Invoke-Client '--command' $one @($focusCommand)
    Invoke-Client '--sync-once' $one @($address,$keyFile); Invoke-Client '--sync-once' $two @($address,$keyFile)
    $distracted=(Inspect-Client $two).records[0]
    if ($distracted.id -ne $second.records[0].id -or $distracted.segments[-1].kind -ne 'distraction') { throw '分心状态未完整跨端同步' }
    Apply-ActiveEdit $two $distracted '选择午餐'
    Invoke-Client '--sync-once' $two @($address,$keyFile); Invoke-Client '--sync-once' $one @($address,$keyFile)
    $editedDistraction=(Inspect-Client $one).records[0]
    if ($editedDistraction.task -ne '专注更正' -or $editedDistraction.segments[-1].task -ne '选择午餐' -or $editedDistraction.segments[-1].started -ne $distracted.segments[-1].started) { throw '分心改名污染原任务或时间' }
    @{action='resume-focus';now=($start+2000)} | ConvertTo-Json | Set-Content -LiteralPath $focusCommand -Encoding UTF8
    Invoke-Client '--command' $two @($focusCommand)
    Invoke-Client '--sync-once' $two @($address,$keyFile); Invoke-Client '--sync-once' $one @($address,$keyFile)
    $resumed=(Inspect-Client $one).records[0]
    if ($resumed.segments.Count -ne 3 -or $resumed.segments[-1].kind -ne 'focus') { throw '恢复状态未完整跨端同步' }
    Apply-ActiveEdit $one $resumed '恢复后更正'
    Invoke-Client '--sync-once' $one @($address,$keyFile); Invoke-Client '--sync-once' $two @($address,$keyFile)
    $afterRename=(Inspect-Client $two).records[0]
    if ($afterRename.task -ne '恢复后更正' -or $afterRename.segments.Count -ne 3 -or $afterRename.segments[1].task -ne '选择午餐') { throw '恢复后改名未同步或制造新分段' }
    # 两端离线编辑同一活动版本，既有合并规则必须保留双方完整元数据和时间线。
    Apply-ActiveEdit $one $afterRename '活动离线甲'
    Apply-ActiveEdit $two $afterRename '活动离线乙'
    Invoke-Client '--sync-once' $one @($address,$keyFile); Invoke-Client '--sync-once' $two @($address,$keyFile); Invoke-Client '--sync-once' $one @($address,$keyFile)
    $activeBranches=@((Inspect-Client $one).conflicts | ForEach-Object { $_.entity.task })
    if ('活动离线甲' -notin $activeBranches -or '活动离线乙' -notin $activeBranches) { throw '活动离线修改丢失分支' }
    # 使用固定边界结束，避免测试执行速度影响恢复后区间长度。
    @{action='finish';now=($start+3000)} | ConvertTo-Json | Set-Content -LiteralPath $focusCommand -Encoding UTF8
    Invoke-Client '--command' $two @($focusCommand)

    Invoke-Client '--sync-once' $two @($address,$keyFile)
    Invoke-Client '--sync-once' $one @($address,$keyFile)
    $first=Inspect-Client $one
    # 既定冲突策略分别保留双方时间线，结束一个分支不会隐式结束另一个。
    # 对仍显示的活动分支再次明确结束，再检查两端最终均可进入历史管理。
    if ($null -eq $first.records[0].ended) {
        @{action='finish';now=($start+3000)} | ConvertTo-Json | Set-Content -LiteralPath $focusCommand -Encoding UTF8
        Invoke-Client '--command' $one @($focusCommand)
        Invoke-Client '--sync-once' $one @($address,$keyFile); Invoke-Client '--sync-once' $two @($address,$keyFile); Invoke-Client '--sync-once' $one @($address,$keyFile)
        $first=Inspect-Client $one
    }
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
        $reply=Invoke-RestMethod "$address/v1/sync" -Method Post -Headers @{Authorization="Bearer $token";"X-Lyushi-Protocol"="4"} -ContentType application/json -Body $body
        $serverId=$reply.serverId; $cursor=$reply.cursor
    }
    Apply-Edit $one $base 'pending-across-pages'
    Invoke-Client '--sync-once' $one @($address,$keyFile)
    $final=Inspect-Client $one
    if ($final.records[0].task -ne 'pending-across-pages' -or $final.pending -ne 0) { throw '分页覆盖了本机新内容' }

    # 标签跨设备新增、任务关联、删除及历史快照均通过真实客户端进程验证。
    $tagCommand=Join-Path $sandbox 'tag-command.json'
    @{action='add-tag';name='同步标签'}|ConvertTo-Json|Set-Content -LiteralPath $tagCommand -Encoding UTF8
    Invoke-Client '--command' $one @($tagCommand)
    $tagged=(Inspect-Client $one).records[0]
    @{action='tag-record';id='';ledgerId=$tagged.ledgerId;task='独立标签任务';started=$tagged.started;ended=$tagged.ended;tag='同步标签'}|ConvertTo-Json|Set-Content -LiteralPath $tagCommand -Encoding UTF8
    Invoke-Client '--command' $one @($tagCommand)
    $taggedEntry=(Inspect-Client $one).records | Where-Object {$_.task -eq '独立标签任务'}
    Invoke-Client '--sync-once' $one @($address,$keyFile); Invoke-Client '--sync-once' $two @($address,$keyFile)
    $taggedRemote=Inspect-Client $two
    if (($taggedRemote.records | Where-Object {$_.id -eq $taggedEntry.id}).tag -ne '同步标签' -or @($taggedRemote.tags | Where-Object {-not $_.deleted}).Count -ne 1) { throw '标签新增或任务标签未同步' }
    @{action='delete-tag';name='同步标签'}|ConvertTo-Json|Set-Content -LiteralPath $tagCommand -Encoding UTF8
    Invoke-Client '--command' $two @($tagCommand); Invoke-Client '--sync-once' $two @($address,$keyFile); Invoke-Client '--sync-once' $one @($address,$keyFile)
    $afterTagDelete=Inspect-Client $one
    if (@($afterTagDelete.tags | Where-Object {-not $_.deleted}).Count -ne 0 -or ($afterTagDelete.records | Where-Object {$_.id -eq $taggedEntry.id}).tag -ne '同步标签') { throw '标签删除未同步或历史标签丢失' }

    # 明确删除覆盖全部已知分支，仍保留冲突档案；另一端收到删除状态。
    Invoke-Client '--sync-once' $two @($address,$keyFile)
    $delete=Join-Path $sandbox 'delete.json'; @{action='delete';id=$base.id}|ConvertTo-Json|Set-Content -LiteralPath $delete -Encoding UTF8
    Invoke-Client '--command' $one @($delete); Invoke-Client '--sync-once' $one @($address,$keyFile); Invoke-Client '--sync-once' $two @($address,$keyFile)
    if (-not ((Inspect-Client $two).records | Where-Object {$_.id -eq $base.id}).deleted) { throw '删除未传播' }
    $summary=@{passed=$true;activeMetadata=$true;activeMetadataBranches=$true;tagLifecycle=$true;distractionCrossDevice=$true;crossDeviceStop=$true;offlineBranches=$true;paginationProtectsPending=$true;deletePropagation=$true;directory=$sandbox}
    $summary|ConvertTo-Json|Set-Content -LiteralPath "$projectDirectory/build/sync-local-result.json" -Encoding UTF8
    $summary|ConvertTo-Json
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id }
}
