import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun loadProps(path: String) = Properties().apply {
    val f = rootProject.file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

val versionProps = loadProps("version.properties")
val signingProps = loadProps("signing/keystore.properties")

// 업데이트/원격 설정 주소. update.repo(owner/name)만 지정하면 GitHub 기본 주소를 씁니다.
val updateRepo = (findProperty("update.repo") as String?).orEmpty().trim()
val manifestUrl = (findProperty("update.manifestUrl") as String?)?.trim().orEmpty().ifEmpty {
    if (updateRepo.isEmpty()) "" else "https://github.com/$updateRepo/releases/latest/download/latest.json"
}
val remoteConfigUrl = (findProperty("update.configUrl") as String?)?.trim().orEmpty().ifEmpty {
    if (updateRepo.isEmpty()) "" else "https://raw.githubusercontent.com/$updateRepo/main/remote/config.json"
}

android {
    namespace = "dev.localtrans.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.localtrans.app"
        minSdk = 31
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode", "1").toInt()
        versionName = versionProps.getProperty("versionName", "0.1.0")

        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$manifestUrl\"")
        buildConfigField("String", "REMOTE_CONFIG_URL", "\"$remoteConfigUrl\"")
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("shared") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // 디버그와 릴리스를 같은 키로 서명해야 adb 설치본과 OTA 설치본이 서로 덮어쓸 수 있습니다.
        val shared = signingConfigs.findByName("shared")
        debug {
            if (shared != null) signingConfig = shared
        }
        release {
            isMinifyEnabled = false
            if (shared != null) signingConfig = shared
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // 온디바이스 LLM (Gemma 4 E2B)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    // 스트리밍 음성 인식 (scripts/fetch-deps.sh 로 받음)
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-2.0.1.aar"))

    testImplementation("junit:junit:4.13.2")
}
