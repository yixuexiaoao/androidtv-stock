plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.split(".").map { it.toInt() }

base.archivesName.set("androidtv-stock-$appVersion")

android {
    namespace = "io.github.yixuexiaoao.tvstock"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yixuexiaoao.tvstock"
        minSdk = 21
        targetSdk = 34
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            // 优先使用 CI Secrets 提供的私有证书；未配置时使用仓库内的公开侧载证书，
            // 保证每次构建签名一致、可覆盖升级（详见 README「签名说明」）。
            val privateStore = System.getenv("STOCK_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            if (privateStore != null) {
                storeFile = file(privateStore)
                storePassword = System.getenv("STOCK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STOCK_KEY_ALIAS")
                keyPassword = System.getenv("STOCK_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("signing/sideload.keystore")
                storePassword = "android"
                keyAlias = "sideload"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 输出 armeabi-v7a(v7)、arm64-v8a(v8) 以及通用包
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
