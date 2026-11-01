// 仅使用系统控件及 SQLite，保持客户端依赖简单。
plugins { id("com.android.application") }
android {
    namespace = "dev.lyushi.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.lyushi.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.0"
    }
    buildFeatures { buildConfig = true }
}
