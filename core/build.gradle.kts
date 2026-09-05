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
        namespace = "com.dhiachemingui.kplayer.core"
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

    sourceSets {

        // Android and desktop throw the same JDK exceptions — both read through
        // java.net — so the JDK half of the error classification is written once,
        // here, instead of once per JVM target.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)

        // The two Apple backends reach AVFoundation by completely different routes
        // — Kotlin/Native interop on iOS, JNA `objc_msgSend` on desktop — but both
        // end up holding an `NSError` domain and code, so they classify through one
        // table. It cannot live in commonMain (that would put Apple's vocabulary in
        // front of Android and the browser) and it must not be written twice, so it
        // lives in a source set the two of them share and nobody else sees.
        val appleSharedMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(appleSharedMain)

        // `iosMain` is the one source set here the default hierarchy template
        // creates rather than the targets themselves, and configuring an edge on it
        // by hand is what makes the template skip it — leaving the three iOS targets
        // wired to nothing but commonMain. So the two edges the template would have
        // drawn are drawn explicitly below; without them every `actual` in iosMain
        // silently disappears from the compilation.
        iosMain.get().dependsOn(appleSharedMain)
        listOf("iosX64Main", "iosArm64Main", "iosSimulatorArm64Main").forEach {
            getByName(it).dependsOn(iosMain.get())
        }

        commonMain.dependencies {

            // api, not implementation: PlaybackStatus / PlaybackEvent implement the
            // state-machine's State / Event interfaces, so they are part of this
            // module's public API.
            api(project(":state-machine"))

            // api: AudioSession.interruptions and MediaPlayer.state expose Flow /
            // StateFlow in their public signatures.
            api(libs.kotlin.coroutines.core)

        }

        jvmMain.dependencies {
            // Dispatchers.Main has no implementation on the JVM unless one is on the
            // runtime classpath, and `EngineMediaPlayer`'s action scope defaults to
            // it — so without this a desktop player built on the default throws
            // "Module with the Main dispatcher is missing" on its first command.
            // It belongs here rather than in :sample because :core is what requires
            // it; a desktop app depending on the library must not have to know. It
            // stays `implementation` and still reaches :session at runtime, which is
            // all `Dispatchers.Main` needs — nothing compiles against Swing types.
            // Swing rather than JavaFX because Compose Desktop composes on the AWT
            // event thread, which is exactly what this dispatcher targets.
            implementation(libs.kotlin.coroutines.swing)
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

    coordinates(group.toString(), "core", version.toString())

    pom {
        name = "kplayer-core"
        description = "Playback contracts, audio session ownership, interruption policy engine and session wiring."
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