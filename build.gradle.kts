plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlinMultiplatform) apply  false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dokka)
}

// Aggregates the per-module API docs published below into one site.
// `./gradlew :dokkaGenerate` outputs the combined HTML to build/dokka/html.
dokka {
    moduleName.set("kplayer")
}

dependencies {
    dokka(project(":core"))
    dokka(project(":session"))
    dokka(project(":audio"))
    dokka(project(":video"))
    dokka(project(":state-machine"))
    dokka(project(":ui"))
}
