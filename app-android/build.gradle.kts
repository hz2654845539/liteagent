plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.liteagent.app"
    compileSdk = 35
    ndkVersion = "28.0.12433566"

    defaultConfig {
        applicationId = "com.liteagent.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 只发 arm64：MNN 官方只构建 64 位，且端侧 LLM 在 32 位机上没有实用价值
        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                // libMNN.so 用 c++_static 编译，这里必须一致，
                // 否则两套 STL 实例跨库传递 std::string 会崩
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // 用 debug 签名仅为让 release 包能直接装机验证体积，正式发布要换自己的 keystore
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    // 16KB page size（Android 15+）：MNN 的 libMNN.so 也按此对齐
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 规则表单一源：仓库根 rules/ 是唯一真相，构建时同步进 assets。
// 注意目录层级：必须落到 assets/rules/ 而不是 assets/ 根，
// 否则代码里 assets.open("rules/recommend-rules.json") 会静默失败（runCatching 吞掉）
val rulesOutDir = layout.buildDirectory.dir("generated/assets/rules")
val syncRecommendRules by tasks.registering(Copy::class) {
    from(rootProject.file("rules"))
    into(rulesOutDir)
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/assets"))
tasks.named("preBuild").configure { dependsOn(syncRecommendRules) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)

    // 中文 OCR（图片 → 文字）。选 ML Kit 而不是视觉大模型的原因见 docs/08-image-input.md：
    // Qwen2-VL 最小 2B，这台 2GB 设备跑不动；而 OCR 只需要几十 MB，能把"拍文档问答"跑通。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
