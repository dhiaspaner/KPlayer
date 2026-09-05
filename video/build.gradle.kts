import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "com.dhiachemingui.kplayer"
version = "0.0.0"

kotlin {
    jvm()
    androidLibrary {
        namespace = "com.dhiachemingui.kplayer.video"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(
                    JvmTarget.JVM_11
                )
            }
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        // KVO on AVPlayer's `rate` / `status` / `playbackLikelyToKeepUp` needs the
        // NSKeyValueObserving category, which isn't in the default Foundation bindings.
        compilations["main"].cinterops {
            create("nskeyvalueobserving") {
                defFile(
                    project.file(
                        "src/nativeInterop/cinterop/nskeyvalueobserving.def"
                    )
                )
            }
        }
    }

    sourceSets {

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":session"))
            implementation(project(":state-machine"))
        }

        androidMain.dependencies {
            implementation(libs.media3.exoplayer)
        }

        jvmMain.dependencies {
            implementation(libs.gstreamer)
            // The desktop engines are pure JNA: AVFoundation through the ObjC
            // runtime on macOS, MFPlay through COM on Windows. No native shim is
            // built, so there is nothing to compile per-OS.
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }

        wasmJsMain.dependencies {
            // org.w3c.dom bindings for the <video> element; :core api-exposes the
            // same artifact, but declaring it keeps the dependency honest.
            implementation(libs.kotlinx.browser)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.coroutines.test)
        }

        iosTest {
            resources.srcDir("src/iosTest/resources")
        }
        iosArm64Test.dependencies {}
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "video", version.toString())

    pom {
        name = "kplayer-video"
        description = "Video playback backends — ExoPlayer on Android, AVPlayer on iOS."
        inceptionYear = "2024"
        url = "https://github.com/dhiaspaner/kplayer"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }

        developers {
            developer {
                id = "dhiaspaner"
                name = "Dhia Chemingui"
                url = "https://github.com/dhiaspaner"
                email = "dhia.cheminguid@gmail.com"
            }
        }

        scm {
            url = "https://github.com/dhiaspaner/kplayer"
            connection = "scm:git:git://github.com/dhiaspaner/kplayer.git"
            developerConnection = "scm:git:ssh://git@github.com/dhiaspaner/kplayer.git"
        }
    }
}