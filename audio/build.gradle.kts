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
        namespace = "com.dhiachemingui.kplayer.audio"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(JvmTarget.JVM_11)
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
            // api, not implementation: MediaPlayer, MediaSource and PlaybackState
            // appear in this module's public signatures (AudioPlayer(),
            // AbstractAudioPlayer), so consumers need them on the compile classpath.
            api(project(":core"))

            // api as well, and for the same reason: AudioPlayer() takes an
            // InterruptionConfig and an AudioSessionMode and returns the
            // KMediaManager that owns them. :session api-exposes :core, so this
            // line alone would suffice — both are named because both are used
            // directly, and a reader should not have to know which module holds
            // which type.
            api(project(":session"))

            implementation(project(":state-machine"))

            // api: AudioPlayer exposes StateFlow / SharedFlow in its public signature.
            api(libs.kotlin.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.media3.exoplayer)
        }

        jvmMain.dependencies {
            implementation(libs.gstreamer)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "audio", version.toString())

    pom {
        name = "kplayer-audio"
        description = "Audio playback backends — ExoPlayer on Android, AVPlayer on iOS."
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