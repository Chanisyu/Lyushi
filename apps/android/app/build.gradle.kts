// 仅使用系统控件及 SQLite，保持客户端依赖简单。
plugins { id("com.android.application") }
android {
    namespace = "dev.lyushi.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.lyushi.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "0.8.1"
    }
    // Android 和桌面从同一份 PNG 读取粉白 Ly 图案，避免各平台图标更新遗漏。
    sourceSets.named("main") { res.directories.add(rootProject.file("../../assets/branding/res").absolutePath) }
    buildFeatures { buildConfig = true }
}
