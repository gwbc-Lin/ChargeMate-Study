plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional integrations require separate runtime and publication rights.
// Live2D runtime files are not bundled; enable it only after preparing your own licensed copy.
val live2dEnabled = providers.gradleProperty("live2dEnabled").orElse("false").get() == "true"
val spineEnabled = providers.gradleProperty("spineEnabled").orElse("true").get() == "true"
listOf("live2d" to live2dEnabled).forEach { (kind, enabled) ->
    if (enabled) {
        check(file("optional/$kind/assets/live2d/$kind-runtime-manifest.json").isFile) {
            "Prepare your separately licensed $kind runtime with tools/prepare_optional_runtime.py first."
        }
    }
}

val live2dPublicationApproved = providers.gradleProperty("live2dPublicationApproved").orNull == "true"

android {
    namespace = "com.wirelesspet"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.wirelesspet"
        minSdk = 29
        targetSdk = 37
        versionCode = 47
        versionName = "0.8.0-public"
        buildConfigField("boolean", "LIVE2D_ENABLED", live2dEnabled.toString())
        buildConfigField("boolean", "SPINE_ENABLED", spineEnabled.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // M4 之前不做混淆与资源压缩，保留可读的崩溃栈用于真机诊断。
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            if (live2dEnabled) assets.srcDir("optional/live2d/assets")
            if (spineEnabled) assets.srcDir("src/spine/assets")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // 首版按 M1/M2 迭代，lint 告警不阻塞构建；发布前再收紧。
        abortOnError = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Always run this prerequisite, including when release packaging is up-to-date.
val verifyLive2dPublication = tasks.register("verifyLive2dPublication") {
    val approvedForThisBuild = !live2dEnabled || live2dPublicationApproved
    doLast {
        check(approvedForThisBuild) {
            "Live2D publication rights are pending. Obtain applicable approval before release; then explicitly set -Plive2dPublicationApproved=true."
        }
    }
}
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") dependsOn(verifyLive2dPublication)
}
