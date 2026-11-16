plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 구글 번역 오프라인 모드와 같은 모델을 쓰는 ML Kit 온디바이스 번역을 재는 벤치마크 전용 앱.
// 본 앱과 분리해, 본 앱에는 ML Kit 의존성이 들어가지 않게 한다.
android {
    namespace = "dev.localtrans.benchmlkit"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.localtrans.benchmlkit"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
}
