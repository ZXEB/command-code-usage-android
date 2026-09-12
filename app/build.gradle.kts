import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * 签名配置。
 *
 * 密钥来自 CI secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）。
 * 四个都齐了才启用 release 签名；缺任何一个就自动降级为未签名 release + debug 构建，
 * 保证 CI 永远不会因为密钥问题变红。
 */
val keystoreBase64: String? = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val keyAlias: String? = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val keyPassword: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val hasSigningKey = listOf(keystoreBase64, keystorePassword, keyAlias, keyPassword).all { it != null }

val releaseKeystore = layout.buildDirectory.file("signing/release.p12")
val prepareSigningKey by tasks.registering {
    onlyIf { hasSigningKey }
    val out = releaseKeystore
    val data = keystoreBase64
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeBytes(Base64.getDecoder().decode(data))
    }
}

android {
    namespace = "dev.zxeb.ccusage"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.zxeb.ccusage"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh", "en")
    }

    if (hasSigningKey) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore.get().asFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
                "META-INF/*.kotlin_module",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 有密钥时先解出 keystore，再交给打包任务
if (hasSigningKey) {
    tasks.matching { it.name.startsWith("package") || it.name.contains("SigningConfig") }.configureEach {
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
