plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.donodavez.map"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":property"))
    api(libs.maplibre.android)
    testImplementation(libs.junit)
}
