plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.deskpet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.deskpet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 小奈的 Supabase 后端（publishable key 设计为客户端可公开使用）
        buildConfigField("String", "SUPABASE_URL", "\"https://qgtsfgviyagkeafqyqyo.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_dxfFmg840ZpDF-8atWJTtw_uPdehf_v\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // 小米/MIUI 安装器对只有 v2 签名的 APK 报"解析包出现问题"。
        // 隐式 debug 配置的 enableV1Signing 在 AGP 8.x Kotlin DSL 下不生效,
        // 这里显式创建签名配置并绑定到 debug buildType,强制 v1+v2。
        create("deskpetDebug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "AndroidDebugKey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("deskpetDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
