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

fun getProp(key: String, defaultVal: String): String {
    return System.getenv(key.replace('.', '_').uppercase())
        ?: (project.findProperty(key) as? String)
        ?: localProperties.getProperty(key)
        ?: defaultVal
}

val serverIp: String = getProp("server.ip", "192.168.1.11")
val serverRestPort: Int = getProp("server.rest_port", "8080").toIntOrNull() ?: 8080
val serverWsPort: Int = getProp("server.ws_port", "8080").toIntOrNull() ?: 8080
val serverMqttPort: Int = getProp("server.mqtt_port", "1883").toIntOrNull() ?: 1883
val serverUdpPort: Int = getProp("server.udp_port", "9090").toIntOrNull() ?: 9090
val serverMediaPort: Int = getProp("server.media_port", "9000").toIntOrNull() ?: 9000

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
        buildConfigField("int", "REST_PORT", "$serverRestPort")
        buildConfigField("int", "WS_PORT", "$serverWsPort")
        buildConfigField("int", "MQTT_PORT", "$serverMqttPort")
        buildConfigField("int", "UDP_PORT", "$serverUdpPort")
        buildConfigField("int", "MEDIA_PORT", "$serverMediaPort")
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
    // Retrofit for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    // AndroidX Security Crypto for encrypted shared preferences
    implementation("androidx.security:security-crypto:1.1.0-alpha03")
    // MQTT Client
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
