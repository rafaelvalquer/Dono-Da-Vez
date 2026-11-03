plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.donodavez.property"
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}
