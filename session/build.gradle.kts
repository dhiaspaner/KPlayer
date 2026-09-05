import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        namespace = "com.dhiachemingui.kplayer.session"
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

    // No intermediate source sets, and therefore no manual `dependsOn`: everything
    // here is either common or written once per target. That leaves the default
    // hierarchy template applied, unlike :core, which has to redraw the iOS edges by
    // hand because it configures `iosMain` itself.

    sourceSets {

        commonMain.dependencies {

            // api, not implementation: this module hands a KMediaManager back as a
            // MediaPlayer, takes a MediaPlayer in, and reduces PlayerState — :core's
            // types are all over its public signatures, so a consumer of :session
            // needs them on its compile classpath and never has to name :core.
            implementation(project(":core"))

            // api: AudioSession.interruptions and KMediaManager.state expose Flow /
            // StateFlow in their public signatures.
            api(libs.kotlin.coroutines.core)
        }

        androidMain.dependencies {
            // ProcessLifecycleOwner, for the app-wide foreground/background signal
            // the background policy acts on. Only the observers need it, which is
            // why it lives here and not in :core.
            implementation(libs.androidx.lifecycle.process)
        }

        jvmMain.dependencies {
            // CoreAudio, for the macOS output-route observer. Plain C functions,
            // so no Objective-C runtime is involved — which is the point: JNA
            // cannot synthesise the blocks NSNotificationCenter would need, but a
            // JNA Callback is exactly the C function pointer CoreAudio wants.
            implementation(libs.jna)
        }

        wasmJsMain.dependencies {
            // api: the web LifecycleObserver and audio session are built on
            // org.w3c.dom, and :audio's HtmlAudioEngine needs the same bindings.
            api(libs.kotlinx.browser)
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

    coordinates(group.toString(), "session", version.toString())

    pom {
        name = "kplayer-session"
        description = "Audio session ownership, interruption policy, system observers and the KMediaManager that wires them to a player."
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
