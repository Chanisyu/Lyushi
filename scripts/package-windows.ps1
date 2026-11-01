# 用途：用本机已验证的 Qt 工具构建并整理可解压运行的 Windows 本地测试版。
# 脚本只写 build 与 dist，不修改系统 PATH 或安装全局软件。
param([string]$QtDirectory = $env:LYUSHI_QT_DIR, [string]$ToolchainDirectory = $env:LYUSHI_TOOLCHAIN_DIR)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
# 工具目录由参数指定；项目缓存仅作为默认值，不修改全局环境。
if (-not $QtDirectory) { $QtDirectory = Join-Path $projectDirectory '.tools/qt/ucrt64' }
if (-not $ToolchainDirectory) { $ToolchainDirectory = $QtDirectory }
$qtDirectory = [System.IO.Path]::GetFullPath($QtDirectory)
$toolchainDirectory = [System.IO.Path]::GetFullPath($ToolchainDirectory)
$buildDirectory = Join-Path $projectDirectory 'build/desktop-windows'
$outputDirectory = Join-Path $projectDirectory 'dist/Lyushi-0.4.0'
$previousPath = $env:PATH
try {
    $env:PATH = "$qtDirectory/bin;$toolchainDirectory/bin;$previousPath"
    cmake -S "$projectDirectory/apps/desktop" -B $buildDirectory -G Ninja -DCMAKE_BUILD_TYPE=Release "-DCMAKE_CXX_COMPILER=$toolchainDirectory/bin/g++.exe" "-DCMAKE_MAKE_PROGRAM=$qtDirectory/bin/ninja.exe" "-DCMAKE_PREFIX_PATH=$qtDirectory"
    if ($LASTEXITCODE -ne 0) { throw 'CMake 配置失败' }
    cmake --build $buildDirectory --parallel 3
    if ($LASTEXITCODE -ne 0) { throw '桌面编译失败' }
    # 为新版本使用独立目录，避免覆盖用户正在运行的旧版；仅重建本脚本产生的目录；删除前验证绝对路径和目录本身不是重解析链接。
    $expectedOutput = [System.IO.Path]::GetFullPath((Join-Path $projectDirectory 'dist/Lyushi-0.4.0'))
    if ([System.IO.Path]::GetFullPath($outputDirectory) -ne $expectedOutput) { throw '打包目标不在指定输出目录' }
    if (Test-Path -LiteralPath $outputDirectory) {
        $existingOutput = Get-Item -LiteralPath $outputDirectory -Force
        if ($existingOutput.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw '输出目录不能是链接' }
        if ((Resolve-Path -LiteralPath $outputDirectory).Path -ne $expectedOutput) { throw '输出目录解析结果不符' }
        Remove-Item -LiteralPath $outputDirectory -Recurse -Force
    }
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    Copy-Item -LiteralPath "$buildDirectory/lyushi.exe" -Destination $outputDirectory -Force
    & "$qtDirectory/bin/windeployqt.exe" --release --compiler-runtime --no-translations --no-system-d3d-compiler --no-system-dxc-compiler --no-opengl-sw --skip-plugin-types generic,imageformats --exclude-plugins qsqlibase,qsqlmysql,qsqlodbc,qsqlpsql --dir $outputDirectory "$outputDirectory/lyushi.exe"
    if ($LASTEXITCODE -ne 0) { throw 'Qt 依赖整理失败' }
    # MSYS2 的 windeployqt 不会完整复制非 Qt 动态库；递归读取 PE 导入表补齐依赖。
    # 系统 DLL 由 Windows 自身提供，其他 DLL 必须在固定工具目录找到，缺失即停止打包。
    $pendingLibraries = [System.Collections.Generic.Queue[string]]::new()
    $scannedLibraries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    Get-ChildItem -LiteralPath $outputDirectory -Recurse -File | Where-Object Extension -in '.exe','.dll' | ForEach-Object { $pendingLibraries.Enqueue($_.FullName) }
    while ($pendingLibraries.Count -gt 0) {
        $binaryPath = $pendingLibraries.Dequeue()
        if (-not $scannedLibraries.Add($binaryPath)) { continue }
        $imports = & "$toolchainDirectory/bin/objdump.exe" -p $binaryPath | Select-String 'DLL Name:\s*(\S+)'
        if ($LASTEXITCODE -ne 0) { throw "读取依赖失败：$binaryPath" }
        foreach ($importLine in $imports) {
            $libraryName = $importLine.Matches[0].Groups[1].Value
            if ($libraryName -match '^(api-ms-|ext-ms-)' -or (Test-Path -LiteralPath (Join-Path $env:SystemRoot "System32/$libraryName"))) { continue }
            $destination = Join-Path $outputDirectory $libraryName
            if (-not (Test-Path -LiteralPath $destination)) {
                $librarySource = @("$qtDirectory/bin/$libraryName", "$toolchainDirectory/bin/$libraryName") | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
                if (-not $librarySource) { throw "缺少运行库：$libraryName" }
                Copy-Item -LiteralPath $librarySource -Destination $destination
            }
            $pendingLibraries.Enqueue($destination)
        }
    }
    # 附带本机 Qt 发行包的许可文件；公开发布前仍需完成整个依赖清单的核查。
    Copy-Item -LiteralPath "$qtDirectory/share/licenses" -Destination $outputDirectory -Recurse -Force
    # ICU 的许可文件位于其版本目录；补齐全局编译器及其他运行库的原始许可文本。
    Get-ChildItem -LiteralPath "$qtDirectory/share/icu" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $icuLicense = Join-Path $_.FullName 'LICENSE'
        if (Test-Path -LiteralPath $icuLicense) {
            $licenseDirectory = Join-Path $outputDirectory ("licenses/icu-" + $_.Name)
            New-Item -ItemType Directory -Path $licenseDirectory -Force | Out-Null
            Copy-Item -LiteralPath $icuLicense -Destination $licenseDirectory
        }
    }
    foreach ($runtime in @('gcc-libs','winpthreads','libiconv','gettext-runtime','libffi','glib2','libpng','sqlite3','zlib','zstd','bzip2')) {
        $runtimeLicense = "$toolchainDirectory/share/licenses/$runtime"
        if (Test-Path -LiteralPath $runtimeLicense) { Copy-Item -LiteralPath $runtimeLicense -Destination "$outputDirectory/licenses/msys2-$runtime" -Recurse -Force }
    }
    # 随包说明使用独立文本，不包含只在项目目录中才能打开的相对链接。
    $bundleNotes = '
柳时 0.4.0

完整解压后运行 lyushi.exe，保留同目录运行库与插件。
新建账本、输入事项后开始计时，结束后查看明细。关闭程序不会结束记录。
时间固定为北京时间。JSON 用于完整备份及合并恢复，CSV 用于表格查看。
同步须填写自托管 HTTPS 地址及本设备密钥；未配置时可单机使用。
项目源码采用 Apache-2.0，第三方依赖保留各自许可证。
详细说明见源码项目 README.md 和 THIRD_PARTY_NOTICES.md。
'
    [System.IO.File]::WriteAllText("$outputDirectory/使用说明.txt", $bundleNotes, [System.Text.UTF8Encoding]::new($false))
    # 发布包同时附带项目许可证与第三方依赖说明。
    foreach ($notice in @('LICENSE','NOTICE','THIRD_PARTY_NOTICES.md')) {
        Copy-Item -LiteralPath (Join-Path $projectDirectory $notice) -Destination $outputDirectory -Force
    }
    Compress-Archive -LiteralPath $outputDirectory -DestinationPath "$projectDirectory/dist/lyushi-windows-0.4.0-beta.zip" -Force
} finally {
    $env:PATH = $previousPath
}
