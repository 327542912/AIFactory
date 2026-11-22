plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aifactory.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.aifactory.app"
        minSdk = 26
        // 必须 <= 28：Android 10+ 对 targetSdk>=29 的应用禁止执行私有目录里的二进制（Runtime 依赖这一点）
        targetSdk = 28
        versionCode = 55
        versionName = "0.7.9"
    }
    signingConfigs {
        // 固定签名：CI 每次构建用同一个 keystore，否则签名变了就只能卸载重装，
        // 而卸载会删除 /data/data/com.aifactory.app（Runtime 与 SDK 全部丢失）。
        create("stable") {
            storeFile = rootProject.file("keystore/aifactory-debug.jks")
            storePassword = "aifactory"
            keyAlias = "aifactory"
            keyPassword = "aifactory"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { signingConfig = signingConfigs.getByName("stable") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
