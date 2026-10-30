// 原生应用共用安卓官方构建工具，不引入网页框架或额外界面引擎。
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "Lyushi"
include(":app")
