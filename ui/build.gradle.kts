import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "com.dhiachemingui.kplayer"
version = "0.0.0"

kotlin {

    // Explicit, because the custom `skikoMain` below adds a dependsOn edge — and
    // doing that without applying the template first silently disables it.
    applyDefaultHierarchyTemplate()

    jvm()
    androidLibrary {
        namespace = "com.dhiachemingui.kplayer.ui"
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

        binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }


    sourceSets {

        // Desktop and iOS draw decoded frames the same way — Skia raster over the
        // engine's BGRA bytes — and Compose backs both with skiko, so the surface
        // is written once and compiled into both. Android is excluded because it
        // has no skiko and needs none: SurfaceView/TextureView keep frames in the
        // compositor. wasmJs is excluded because its <video> element renders
        // itself, so there are no frames to draw.
        //
        // A shared *directory* rather than an intermediate source set on purpose.
        // Adding `skikoMain` with its own dependsOn edges opts this module out of
        // the default hierarchy template, and iosMain then stops being recognised
        // as the actual-provider for commonMain's expects — the whole surface
        // fails to resolve. Nothing in the shared file is expect/actual, so
        // compiling the same source into both compilations is equivalent and
        // cannot disturb the hierarchy.
        // A real intermediate source set, because the shared surface is *called*
        // from `iosMain` and a source set can only see its ancestors — putting the
        // file in the leaves compiles it but leaves it invisible to the caller,
        // and hanging an extra srcDir on `iosMain` does not reach the per-target
        // Kotlin/Native compilations at all.
        //
        // `applyDefaultHierarchyTemplate()` above is what makes this safe. Adding
        // a `dependsOn` edge without it opts the module out of the default
        // template, and `iosMain` then stops being recognised as the
        // actual-provider for `commonMain`'s expects — every `NativeVideoSurface`
        // fails to resolve.
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(skikoMain)
        iosMain.get().dependsOn(skikoMain)

        commonMain.dependencies {

            // api: VideoPlayerController exposes MediaPlayer / VideoPlayerState /
            // MediaSource / InterruptionConfig in its public signatures. :video
            // api-exposes both :core (the player contract and its state) and
            // :session (InterruptionConfig, AudioSessionMode, KMediaManager), so
            // all of them arrive through this one line.
            implementation(project(":core"))
            implementation(project(":session"))
            implementation(project(":video"))


            // Compose Multiplatform UI stack
            implementation(libs.compose.runtime)
            implementation(libs.compose.runtime.saveable)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)

            // @Preview for the control templates. Annotations only, a few KB —
            // the renderer below is what would be heavy, and it stays on Android.
            implementation(libs.compose.ui.tooling.preview)
        }

        wasmJsMain.dependencies {
            // org.w3c.dom bindings for the <video> element the web surface hands
            // to Compose's HTML interop; :video api-exposes the same artifact,
            // but declaring it keeps the dependency honest.
            implementation(libs.kotlinx.browser)
        }

        androidMain.dependencies {
            // PlayerView + AspectRatioFrameLayout for the render surface.
            implementation(libs.media3.ui)
            implementation(libs.media3.exoplayer)
            // LocalLifecycleOwner, for pause-on-ON_PAUSE.
            implementation(libs.androidx.lifecycle.runtime.compose)
            // Renders the common @Preview functions in the IDE. Android Studio
            // draws multiplatform previews through the Android variant, so the
            // renderer has to be on this classpath.
            implementation(libs.compose.ui.tooling)
        }


        jvmMain.dependencies {
            // Native.getComponentPointer, to hand an AWT canvas's HWND to Media
            // Foundation. Desktop-only, and only for the Windows render path —
            // macOS and Linux draw their frames through Compose and need none of it.
            implementation(libs.jna)
        }

        jvmTest.dependencies {
            // The Skia/AWT desktop runtime, for its *native* binaries. Compose's
            // ui artifacts are pure Kotlin, so org.jetbrains.skia.Image fails its
            // static initialiser without this — the desktop surface converts
            // frames through Skia, and a test JVM has no skiko otherwise. Not
            // needed in jvmMain: an app depending on :ui brings it already, which
            // is why the sample renders while this test could not.
            implementation(compose.desktop.currentOs)
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

    coordinates(group.toString(), "ui", version.toString())

    pom {
        name = "kplayer-ui"
        description = "Compose Multiplatform player UI — controls, render surface, and video/audio integration."
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