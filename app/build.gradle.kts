plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release 签名材料不随仓库分发（release.keystore 已在 .gitignore 中）。
// 优先使用环境变量或 Gradle 属性，缺省值仅用于本机既有密钥库，避免把密码硬编码成唯一来源。
val releaseKeystoreFile = rootProject.file("release.keystore")
val hasReleaseKeystore = releaseKeystoreFile.exists()
val releaseStorePassword = System.getenv("AIAGENT_STORE_PASSWORD")
    ?: providers.gradleProperty("aiagent.storePassword").orNull
    ?: "aiagent123"
val releaseKeyAlias = System.getenv("AIAGENT_KEY_ALIAS")
    ?: providers.gradleProperty("aiagent.keyAlias").orNull
    ?: "aiagent"
val releaseKeyPassword = System.getenv("AIAGENT_KEY_PASSWORD")
    ?: providers.gradleProperty("aiagent.keyPassword").orNull
    ?: "aiagent123"

android {
    namespace = "com.aiagent.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aiagent.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 只有在密钥库存在时才创建 release 签名配置；克隆仓库未携带密钥库时，
        // assembleRelease 仍可完成并产出未签名 APK，不会因缺失文件直接失败。
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
