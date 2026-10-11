import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

val serverIp: String = System.getenv("SERVER_IP")
    ?: localProperties.getProperty("server.ip")
    ?: "192.168.112.72"

android {
    namespace = "com.example.myapplication"
    // Giữ nguyên cấu hình SDK của bạn
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SERVER_IP", "\"$serverIp\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Không nén file .tflite và .task để load nhanh hơn
    androidResources {
        noCompress += listOf("tflite", "task")
    }

    // Fix lỗi 16KB ELF alignment: không nén file .so để giữ nguyên alignment
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    buildFeatures {
        buildConfig = true
        mlModelBinding = true
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // TensorFlow Lite (LiteRT V1 — hỗ trợ GPU Delegate với Interpreter API)
    implementation(libs.litert)
    implementation(libs.litert.gpu)         // GPU Delegate runtime
    implementation(libs.litert.gpu.api)     // GPU Delegate API (CompatibilityList, GpuDelegate)

    // ML Kit Face Detection - thay thế MediaPipe
    // Dùng Google Play Services: không bundle .so vào APK → không bao giờ lỗi 16KB
    implementation(libs.mlkit.face.detection)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation(libs.mlkit.pose.detection)

    // Location API
    implementation(libs.play.services.location)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    // MQTT Client
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
