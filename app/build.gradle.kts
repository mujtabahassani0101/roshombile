plugins { id("com.android.application");id("org.jetbrains.kotlin.plugin.compose") }

android {
    namespace = "com.haseebullahjan.roshd"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.haseebullahjan.roshd"
        minSdk = 24
        targetSdk = 36
        versionCode = 4245
        versionName = "1.1.59"
        testInstrumentationRunner = providers.gradleProperty("qaRunner").orNull ?: "com.haseebullahjan.roshd.qa.NativeSmokeTest"
        ndk { abiFilters += (providers.gradleProperty("abis").orNull ?: "arm64-v8a,x86_64").split(",") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DOMR_BUILD_TESTS=OFF", "-DANDROID_STL=c++_shared", "-DROSHD_REBUILD_SOURCE_ENGINES=ON")
            }
        }
    }
    buildFeatures { buildConfig = true;compose = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug { applicationIdSuffix = ".nativeqa" }
        release {
            if (providers.gradleProperty("sideBySide").isPresent) applicationIdSuffix = ".amaqa"
            isMinifyEnabled = false
            // Local distribution uses the existing development signing identity.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets.getByName("main") {
        assets.srcDir("src/main/assets")
        jniLibs.srcDir("src/main/jniLibs")
    }
    // QA-only: side-by-side builds may be sampled with simpleperf. Never set for distribution.
    if (providers.gradleProperty("profileable").isPresent) {
        check(providers.gradleProperty("sideBySide").isPresent) { "profileable requires -PsideBySide" }
        sourceSets.getByName("release").manifest.srcFile("src/profileable/AndroidManifest.xml")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += setOf("**/libopencv_java4.so", "**/libc++_shared.so")
        // Rebuilt release engines use the normal Android symbol stripping step.
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    testBuildType = providers.gradleProperty("qaBuildType").orNull ?: "debug"
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

// The existing Backdrop dependency contains Java 21 classes. Android receives DEX;
// JVM lifecycle tests need a Java 21 launcher to load that same library directly.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

dependencies {
    // AndroidLiquidGlass / Backdrop: official Android artifact, pinned for compileSdk 36.
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("androidx.compose.foundation:foundation:1.10.3")
    implementation("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.activity:activity-compose:1.13.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("org.opencv:opencv:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.json:json:20250107")
}
