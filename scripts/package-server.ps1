# 收集当前实际链接的 Go 模块许可，不包含设备密钥或业务数据库；不修改服务器。
param([string]$GoExecutable = 'go')
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
# 读取实际选定工具链的运行时许可证。
$goExecutable = (Get-Command $GoExecutable -ErrorAction Stop).Source
$goRoot = & $goExecutable env GOROOT
if ($LASTEXITCODE -ne 0) { throw '无法确定 Go 工具链目录' }
$outputDirectory = Join-Path $projectDirectory 'dist/server-licenses'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Push-Location (Join-Path $projectDirectory 'server')
try {
    $dependencies = & $goExecutable list -deps -f '{{if .Module}}{{.Module.Path}}|{{.Module.Version}}|{{.Module.Dir}}{{end}}' .
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
    Copy-Item -LiteralPath (Join-Path $projectDirectory 'build/lyushi-server-linux-amd64') -Destination (Join-Path $projectDirectory 'dist/lyushi-server-linux-amd64') -Force
    "Collected licenses for $($inventory.Count) linked Go modules and the Go runtime."
} finally {
    Pop-Location
}
