plugins {
    alias(libs.plugins.android.application)
}

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

    // Không nén file .tflite để load nhanh hơn
    androidResources {
        noCompress += "tflite"
    }

    // Fix lỗi 16KB ELF alignment: không nén file .so để giữ nguyên alignment
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    buildFeatures {
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
    implementation(libs.mlkit.pose.detection)

    // Location API
    implementation(libs.play.services.location)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
