// 仅使用系统控件及 SQLite，保持客户端依赖简单。
plugins { id("com.android.application") }
android {
    namespace = "dev.lyushi.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.lyushi.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "0.8.0"
    }
    buildFeatures { buildConfig = true }
}
