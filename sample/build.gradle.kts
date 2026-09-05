import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        // outputModuleName decides the emitted script name, which index.html loads.
        // moduleName is an error as of Kotlin 2.2 and gone in 2.3.
        outputModuleName.set("sample")
        browser {
            commonWebpackConfig {
                outputFileName = "sample.js"
            }
        }
        binaries.executable()
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {

            baseName = "SampleApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":video"))
            implementation(project(":session"))
            implementation(project(":ui"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)

            // Local-file picking on all five targets. Sample-only: the library
            // takes a MediaSource and never opens a dialog itself.
            implementation(libs.filekit.dialogs.compose)

//            implementation(libs.kmpnotifier.local)

        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }

        jvmMain.dependencies {
            // The Skia/AWT desktop runtime. Not in the version catalog because the
            // artifact is OS-specific and the Compose plugin resolves it for the host.
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "com.dhiachemingui.kplayer.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.dhiachemingui.kplayer.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }


    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    dependencies {
        testImplementation(libs.junit)
        testImplementation(libs.robolectric)
    }
}

compose.desktop {
    application {
        mainClass = "com.dhiachemingui.kplayer.sample.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "kplayer-sample"
            packageVersion = "1.0.0"
        }
    }
}
