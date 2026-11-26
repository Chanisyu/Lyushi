#!/usr/bin/env bash
# 在当前 Ubuntu 系统构建并打包桌面端，不使用私有根目录或安装生成的软件包。
# 构建与发布输出保存在 build、dist；临时暂存目录使用本机临时文件系统。
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
build_dir="$project_dir/build/desktop-linux"
version=0.8.1
arch=$(dpkg --print-architecture)
cmake -S "$project_dir/apps/desktop" -B "$build_dir" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$build_dir" --parallel 2
mkdir -p "$project_dir/build" "$project_dir/dist"
# 使用唯一目录避免覆盖已有暂存文件；目录保留，便于核对打包内容。
package_dir=$(mktemp -d "${TMPDIR:-/tmp}/lyushi-deb.XXXXXX")
mkdir -p "$package_dir/DEBIAN" "$package_dir/usr/share/doc/lyushi"
chmod 755 "$package_dir" "$package_dir/DEBIAN"
# 由 CMake 一并安装程序、桌面启动项和主题图标，避免包内图标与源码安装不一致。
DESTDIR="$package_dir" cmake --install "$build_dir" --prefix /usr
cat > "$package_dir/DEBIAN/control" <<CONTROL
Package: lyushi
Version: $version
Architecture: $arch
Maintainer: Lyushi contributors
Depends: libc6, libgcc-s1, libstdc++6, libqt6widgets6, libqt6sql6, libqt6network6, libqt6sql6-sqlite, qt6-qpa-plugins, libsecret-tools
Description: Personal ledger-based time tracker with self-hosted sync
CONTROL
for name in LICENSE NOTICE THIRD_PARTY_NOTICES.md; do
    install -m 644 "$project_dir/$name" "$package_dir/usr/share/doc/lyushi/$name"
done
# 明确 Debian 主许可文本入口；动态链接的系统 Qt 包保留其自身版权文件。
install -m 644 "$project_dir/LICENSE" "$package_dir/usr/share/doc/lyushi/copyright"
chmod 644 "$package_dir/DEBIAN/control"
dpkg-deb --build "$package_dir" "$project_dir/dist/lyushi_${version}_${arch}.deb"
