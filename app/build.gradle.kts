import java.util.Properties

val waterProperties = Properties().apply {
    val config = rootProject.file("water.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
fun waterConfig(name: String, fallback: String = ""): String = "\"" +
    (waterProperties.getProperty(name)?.takeIf { it.isNotBlank() } ?: fallback)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""

fun gradleProp(name: String, fallback: String = ""): String = "\"" +
    providers.gradleProperty(name).orElse(fallback).get()
        .trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.fengkao.timetable"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.hmu.muye"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        buildConfigField("String", "WATER_API_GATEWAY", waterConfig("API_GATEWAY", "https://i.ilife798.com/api/v1"))
        buildConfigField("String", "WATER_API_CID", waterConfig("API_CID"))
        buildConfigField("String", "UPDATE_REPO", gradleProp("muye.updateRepo"))
        buildConfigField("String", "UPDATE_MANIFEST_URL", gradleProp("muye.updateManifestUrl"))
    }

    // 签名：正式密钥库放在项目根的 timetable-release.jks，凭据在 keystore.properties。
    // 未提供密钥时生成未签名 release，绝不使用调试签名冒充正式发布。
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file(
                providers.gradleProperty("releaseSigningProperties").getOrElse("keystore.properties")
            )
            val props = Properties()
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            val sf = props.getProperty("storeFile")?.let { p -> rootProject.file(p) }
            if (sf != null && sf.exists()) {
                storeFile = sf
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else {
                logger.lifecycle("未找到 release 密钥库，将生成未签名 release 包")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 私有签名凭据只从本机文件读取；无密钥库时生成未签名 release 包，不阻断 CI/本地验证。
            signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "WATER_API_GATEWAY", "\"\"")
            buildConfigField("String", "WATER_API_CID", "\"\"")
        }
    }

    // 注：ABI 分包（splits.abi）与 defaultConfig.ndk.abiFilters 互斥，不能并存。
    // 这里走更直接的"按 buildType 选 ABI"路线：debug 双 ABI，release 只 arm64-v8a。
    // 如果日后需要给模拟器出 release，把上面 defaultConfig.ndk 改成 isRelease 时仍含两个 ABI，
    // 并解除这行注释启用 splits + androidComponents 的 versionCode 偏移。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // 液态玻璃：SDF 折射 + 色散 + 高光（参考 QWEA0/Liquid-Glass-Android，其 Compose 版为 Kyant0/backdrop）
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)

    // 网络与序列化
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.embedded)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.test)
}
