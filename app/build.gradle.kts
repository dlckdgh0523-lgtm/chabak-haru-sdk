plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.chabak.spike"
    compileSdkVersion("android-37.0") // 이 PC 에 설치된 플랫폼 (dl.google.com 다운로드 실패)
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "ai.chabak.spike"
        minSdk = 28            // 공식 getting-started: API 28 이상
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"
        // 서버 주소. AVD 에서 호스트 PC = 10.0.2.2
        buildConfigField("String", "SERVER_BASE", "\"http://10.0.2.2:3000/v1\"")
        buildConfigField("String", "ENROLL_SECRET", "\"change-me-enroll-secret\"")
    }
    // 이 AVD 에 서명이 다른 ai.chabak.spike(11/10 설치, 출처 불명)가 있어 덮어쓰지 않고 별도 id 로 설치한다
    buildTypes { getByName("debug") { applicationIdSuffix = ".v232" } }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
