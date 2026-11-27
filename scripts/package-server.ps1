# 指定版本时构建并打包 Linux amd64 服务端。省略版本时保留既有产物整理方式。
param(
    [string]$GoExecutable = 'go',
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
# 读取实际选定工具链的运行时许可证。
$go = (Get-Command $GoExecutable -ErrorAction Stop).Source
$goRoot = & $go env GOROOT
if ($LASTEXITCODE -ne 0) { throw '无法确定 Go 工具链目录' }
$distributionDirectory = Join-Path $projectDirectory 'dist'
$artifactName = "lyushi-server-$Version-linux-amd64"
$stagingDirectory = $null
$savedEnvironment = @{}
if ($Version) {
    # 每次构建使用全新的暂存目录，避免以前的二进制或许可证文件混入正式包。
    $stagingDirectory = Join-Path $projectDirectory "build/server-release-$([guid]::NewGuid().ToString('N'))/$artifactName"
    $outputDirectory = Join-Path $stagingDirectory 'server-licenses'
} else {
    $outputDirectory = Join-Path $distributionDirectory 'server-licenses'
}
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Push-Location (Join-Path $projectDirectory 'server')
try {
    if ($Version) {
        # 构建和许可证清点使用同一 Linux 目标配置。纯 Go SQLite 无需目标系统共享库。
        $targets = @{GOOS='linux'; GOARCH='amd64'; CGO_ENABLED='0'}
        foreach ($name in $targets.Keys) {
            $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $targets[$name], 'Process')
        }
        & $go build -trimpath -ldflags "-X main.releaseVersion=$Version" -o (Join-Path $stagingDirectory 'lyushi-server') .
        if ($LASTEXITCODE -ne 0) { throw '构建 Linux amd64 服务端失败' }
    }
    $dependencies = & $go list -deps -f '{{if .Module}}{{.Module.Path}}|{{.Module.Version}}|{{.Module.Dir}}{{end}}' .
    if ($LASTEXITCODE -ne 0) { throw '无法读取已固定的 Go 模块依赖' }
    $inventory = @()
    foreach ($line in ($dependencies | Where-Object { $_ } | Sort-Object -Unique)) {
        $fields = $line -split '\|',3
        if ($fields.Count -ne 3 -or -not $fields[1] -or -not $fields[2]) { continue }
        $moduleDirectory = Join-Path $outputDirectory ($fields[0].Replace('/','_') + '@' + $fields[1])
        New-Item -ItemType Directory -Path $moduleDirectory -Force | Out-Null
        $licenseFiles = @(Get-ChildItem -LiteralPath $fields[2] -File | Where-Object Name -match '^(LICENSE|COPYING|NOTICE)(\.|$|-)')
        if ($licenseFiles.Count -eq 0) { throw "依赖未找到根目录许可文件：$($fields[0])" }
        foreach ($file in $licenseFiles) { Copy-Item -LiteralPath $file.FullName -Destination $moduleDirectory -Force }
        $inventory += [pscustomobject]@{module=$fields[0];version=$fields[1];licenseFiles=@($licenseFiles.Name)}
    }
    Copy-Item -LiteralPath (Join-Path $goRoot 'LICENSE') -Destination "$outputDirectory/Go-runtime-LICENSE" -Force
    $inventory | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$outputDirectory/modules.json" -Encoding UTF8
    if ($Version) {
        # 只拷贝公开分发所需文件，数据库、凭证和本地部署脚本不进入归档。
        $packageFiles = @(
            'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md',
            'docs/account-management.md', 'docs/sync-protocol.md', 'docs/water-tracking.md',
            'scripts/restore-server.py'
        )
        foreach ($file in $packageFiles) {
            $destination = Join-Path $stagingDirectory $file
            New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
            Copy-Item -LiteralPath (Join-Path $projectDirectory $file) -Destination $destination
        }
        # 升级脚本从暂存根目录读取程序与 systemd 单元，发布包直接使用该目录结构。
        foreach ($file in @('lyushi.service', 'Caddyfile.example', 'upgrade-server.sh')) {
            Copy-Item -LiteralPath (Join-Path $projectDirectory "deploy/$file") -Destination $stagingDirectory
        }
        New-Item -ItemType Directory -Path $distributionDirectory -Force | Out-Null
        $zipPath = Join-Path $distributionDirectory "$artifactName.zip"
        Compress-Archive -LiteralPath $stagingDirectory -DestinationPath $zipPath -Force
        Write-Output $zipPath
    } else {
        Copy-Item -LiteralPath (Join-Path $projectDirectory 'build/lyushi-server-linux-amd64') -Destination (Join-Path $distributionDirectory 'lyushi-server-linux-amd64') -Force
    }
    "已收集 $($inventory.Count) 个 Go 模块及 Go 运行时的许可证。"
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    Pop-Location
}
