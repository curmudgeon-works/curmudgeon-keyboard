import com.android.build.api.variant.ApplicationVariant
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "helium314.keyboard"
        minSdk = 21
        targetSdk = 36
        versionCode = 3901
        versionName = "3.9"
        // Settings migrations in AppUpgrade are keyed on this, not on versionCode: the play flavor restarts
        // versionCode at 1, which would otherwise re-run every HeliBoard migration on each Play update.
        // Bump together with versionCode when merging upstream releases that add migrations.
        buildConfigField("int", "MIGRATION_VERSION", "3901")
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    // Play upload key (Play App Signing holds the real signing key). Lives OUTSIDE the repo:
    // ~/.android-keys/curmudgeon-upload.properties with storeFile/storePassword/keyAlias/keyPassword.
    val playKeyProps = Properties().apply {
        val f = File(System.getProperty("user.home"), ".android-keys/curmudgeon-upload.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (playKeyProps.isNotEmpty()) {
            create("play") {
                storeFile = File(playKeyProps.getProperty("storeFile"))
                storePassword = playKeyProps.getProperty("storePassword")
                keyAlias = playKeyProps.getProperty("keyAlias")
                keyPassword = playKeyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            // debug has minify for a smaller APK (GitHub's 25 MB limit when zipped)
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            // the play flavor ships all bundled dictionaries, like upstream (~40 MB APK; en-US only was ~9 MB)
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName = "Curmudgeon_Keyboard_${output.versionName.get()}-${variant.buildType}.apk"
                }
            }
        }
    }

    // "play" is the only flavor: the app for phones, testers and Google Play, swipe-decoding with the in-tree
    // :gesture decoder (docs/gesture-decoder-spec.md). Upstream's "normal" (Google's closed swipe library, which
    // can't ship) and our side-by-side "lab" were removed in 0.1.004. One app on the phones: the debug build type
    // is compiled by CI but not installed; the Swipe Trainer is under Debug settings.
    flavorDimensions += "distribution"
    productFlavors {
        // Build releases with the nouserlib build type so the "load gesture library" setting is gone:
        //   ./gradlew :app:bundlePlayNouserlib
        create("play") {
            dimension = "distribution"
            isDefault = true
            applicationId = "app.curmudgeon.keyboard"
            // versionName major.minor.build, build always 3 digits; versionCode = minor * 1000 + build (+ major * 100000)
            versionCode = 3004
            versionName = "0.3.004"
            if (playKeyProps.isNotEmpty()) signingConfig = signingConfigs.getByName("play")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        target {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // own gesture decoder (both flavors)
    implementation(project(":gesture"))

    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // newer than 2025.11.01 contains androidx.compose.material:material-android:1.10.0, which requires minSdk 23
    // maybe it's possible to use tools:overrideLibrary="androidx.compose.material" as it's not used explicitly, but probably this is just going to crash
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}
