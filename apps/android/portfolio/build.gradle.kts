plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.donodavez.portfolio"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":property"))
    testImplementation(libs.junit)
}
