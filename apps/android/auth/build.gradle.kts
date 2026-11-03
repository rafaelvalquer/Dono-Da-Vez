plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.donodavez.auth"
}

dependencies {
    implementation(project(":core"))
}
