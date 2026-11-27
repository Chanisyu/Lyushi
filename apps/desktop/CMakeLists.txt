# 桌面应用只使用系统窗口与 SQLite；Windows 和 Ubuntu 共用此构建入口。
cmake_minimum_required(VERSION 3.22)
project(Lyushi VERSION 1.0.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Qt6 REQUIRED COMPONENTS Widgets Sql Network)
qt_add_executable(lyushi WIN32 main.cpp profiles.cpp store.cpp focus.cpp focuschecks.cpp activemetadata-checks.cpp syncstore.cpp syncclient.cpp water.cpp waterui.cpp)
target_link_libraries(lyushi PRIVATE Qt6::Widgets Qt6::Sql Qt6::Network)
# 同一版本用于应用设置及两平台安装包。配置时输出纯文本供打包脚本读取。
target_compile_definitions(lyushi PRIVATE LYUSHI_VERSION="${PROJECT_VERSION}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/lyushi-version.txt" "${PROJECT_VERSION}\n")
# 共享 PNG 嵌入 Qt 程序，解压运行时无需寻找外部图标文件。
set(lyushi_branding "${CMAKE_CURRENT_SOURCE_DIR}/../../assets/branding")
set(lyushi_icon_dir "${lyushi_branding}/res/drawable-nodpi")
qt_add_resources(lyushi "branding" PREFIX "/branding" BASE "${lyushi_icon_dir}"
    FILES "${lyushi_icon_dir}/lyushi_icon.png")
if(WIN32)
    # PE 图标由资源管理器读取，Qt 窗口图标不能替代可执行文件中的原生资源。
    enable_language(RC)
    target_sources(lyushi PRIVATE "${lyushi_branding}/lyushi.rc")
    target_include_directories(lyushi PRIVATE "${lyushi_branding}")
    set_property(SOURCE "${lyushi_branding}/lyushi.rc" APPEND PROPERTY OBJECT_DEPENDS "${lyushi_branding}/lyushi.ico")
    # DPAPI 由 Windows 提供，不在设置文件中存放明文设备密钥。
    target_link_libraries(lyushi PRIVATE crypt32)
endif()
if(MSVC)
    target_compile_options(lyushi PRIVATE /utf-8)
endif()
install(TARGETS lyushi RUNTIME DESTINATION bin)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    # 桌面菜单由图标主题解析，源码安装与 DEB 复用同一套安装路径。
    install(FILES lyushi.desktop DESTINATION share/applications)
    install(FILES "${lyushi_icon_dir}/lyushi_icon.png"
        DESTINATION share/icons/hicolor/512x512/apps RENAME lyushi.png)
endif()
