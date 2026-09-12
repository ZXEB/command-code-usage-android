import java.util.Base64

plugins {
    // 注意：AGP 9 起「内置 Kotlin 支持」，**不能**再应用 org.jetbrains.kotlin.android，
    // 否则会报 "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
    // support since AGP 9.0"。Kotlin 编译由 AGP 内置的 KGP 承担。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * 签名配置。
 *
 * 密钥来自 CI secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）。
 * 四个都齐了才启用 release 签名；缺任何一个就只构建 debug，保证 CI 不会因为「没配密钥」变红。
 *
 * 注意命名：这里刻意用 env* 前缀。`signingConfigs { create("release") { ... } }` 的 lambda
 * 接收者本身就是 SigningConfig，里面已经有 `keyAlias` / `keyPassword` 属性；
 * 如果外层变量同名，`this.keyPassword = keyPassword` 会自己赋值给自己（null），
 * 结果打包时报 `SigningConfig "release" is missing required property "keyPassword"`。
 */
val envKeystoreBase64: String? = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }
val envKeystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val envKeyAlias: String? = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val envKeyPassword: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val hasSigningKey = listOf(envKeystoreBase64, envKeystorePassword, envKeyAlias, envKeyPassword).all { it != null }

/** 版本号：CI 传入 run number，本地构建回退到 1。 */
val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName: String = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"

val releaseKeystore = layout.buildDirectory.file("signing/release.p12")
val prepareSigningKey by tasks.registering {
    onlyIf { hasSigningKey }
    val out = releaseKeystore
    val data = envKeystoreBase64
    outputs.file(out)
    doLast {
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeBytes(Base64.getDecoder().decode(data))
    }
}

android {
    namespace = "dev.zxeb.ccusage"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.zxeb.ccusage"
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    if (hasSigningKey) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore.get().asFile
                // 显式用外层变量名，避免与 SigningConfig 自身属性同名遮蔽
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

// 有密钥时先解出 keystore，再交给打包任务
if (hasSigningKey) {
    tasks.matching { it.name.startsWith("package") }.configureEach {
        dependsOn(prepareSigningKey)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
