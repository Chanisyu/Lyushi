# 第三方组件与分发说明

项目自身代码采用 Apache-2.0。该授权不改变所依赖或随二进制分发的第三方组件许可证。

| 组件 | 使用方式 | 许可证与来源 |
| --- | --- | --- |
| Qt 6 Core / Gui / Widgets / Sql / Network | 桌面客户端动态链接 | 各模块适用的 LGPL-3.0、GPL 或商业许可，须按实际选择核对；[Qt 许可说明](https://www.qt.io/licensing/open-source-lgpl-obligations) |
| SQLite | Qt SQLite 插件及 Go 驱动所用数据库 | SQLite 上游公有领域声明及各封装组件许可证 |
| modernc.org/sqlite v1.61.0 | 服务端纯 Go SQLite 驱动 | 模块根目录 LICENSE；固定版本见 server/go.mod |
| Go 运行时及间接模块 | 服务端构建及运行 | 各模块 BSD、MIT 等原始许可；以实际依赖清单为准 |
| ICU、MinGW 运行库及 Qt 传递依赖 | 部分 Windows 发行包附带 | 按实际 DLL 与发行包保留 ICU、GCC Runtime Library Exception 等许可文本 |
| Android 平台与构建工具 | 原生控件、SQLite、HTTPS 与构建 | 使用平台 SDK；具体条款以 Android、Gradle、Kotlin 及相关工具发行版为准 |

## 源码与二进制的边界

公开源码包不附带 Qt、编译器、SDK、设备密钥、数据库或第三方二进制。`server/go.mod` 与 `server/go.sum` 固定服务端依赖版本。源码中未复制前期调研的 Kimai、KimaiTray、CodeTimer Mobile、Super Productivity 或 TimeTagger 的业务实现。

## 桌面发行包

桌面应用使用 Qt 动态链接。发行者应保留动态库替换能力、对应许可证和版权通知，不限制用户为调试替换库而进行的逆向工程。仅放置 LGPL 文本不足以自动完成全部分发义务；发行者还应按所用版本提供完整对应库源码、补丁及必要构建安装信息，或依法提供适用的源码获取承诺。

Qt 官方源码发布位置：<https://download.qt.io/official_releases/qt/>。如使用 MSYS2 Qt，应同时核对对应版本的发行配方及补丁：<https://github.com/msys2/MINGW-packages>。这些上游链接便于定位源码，不替代发行者自身应履行的完整源码提供义务。分发修改过的第三方库时须提供相应修改。

`scripts/package-windows.ps1` 从选定工具目录复制许可文本并检查 DLL 依赖。使用其他 Qt 发行渠道时，许可证目录布局可能不同；发布前必须检查实际生成的 `licenses/` 内容及对应源码资料。

## 服务端发行包

`scripts/package-server.ps1` 使用实际选定的 Go 工具链枚举链接模块，收集模块根目录的 LICENSE、COPYING、NOTICE，另附 Go 运行时许可，输出至 `dist/server-licenses/`。该目录应与服务端二进制共同分发。

本说明不是对全部第三方组件的重新授权。发生差异时，以对应版本的原始许可文本为准。
