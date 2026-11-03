plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.donodavez.profile"
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}
