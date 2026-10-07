import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

// release 签名信息从根目录 keystore.properties 读取（该文件已 gitignore，绝不入库）。
// 文件不存在时 signingConfig 留空，debug 构建不受影响。
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jywr.pcbuapk"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.jywr.pcbuapk"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // keystore.properties 缺失时不配置，保证没带密钥的机器也能编 debug
            if (!keystoreProperties.isEmpty) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // CryptoUtils / UnlockProtocol / PacketCodec 会调用 android.util.Log，
            // 纯 JVM 单测里未处理的 android.* 方法默认抛 "not mocked" 异常。
            // 打开后返回默认值，于是可以对**真实生产代码**做加解密往返与协议编解码测试，
            // 而不必再像 ManualInputValidationTest 那样自己抄一份实现来测。
            isReturnDefaultValues = true
        }
    }
}

// Room schema 导出目录：AppDatabase 已开 exportSchema，
// 把生成的 schema JSON 纳入版本控制后，后续迁移才有可比对的基线。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // ⚠️ material 不能删。它的 Compose 组件一个都没用到，但它提供了
    // res/values/themes.xml 里的父样式 Theme.Material3.DayNight.NoActionBar
    // （Manifest 的 android:theme）。删掉会直接编译失败：
    // "resource style/Theme.Material3.DayNight.NoActionBar not found"。
    implementation(libs.material)

    // MainActivity 继承 FragmentActivity（BiometricPrompt 的前置要求）。
    // 该类虽然也会经 material / zxing-android-embedded 传递进来，
    // 但直接用到的 API 应显式声明，不该依赖间接传递。
    implementation(libs.androidx.appcompat)

    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // DataStore
    implementation(libs.androidx.datastore.preferences)
    
    // Biometric
    implementation(libs.androidx.biometric)
    
    // QR Code
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // WorkManager（保活兜底）
    implementation(libs.androidx.work.runtime.ktx)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}