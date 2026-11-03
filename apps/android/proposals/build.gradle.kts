plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.donodavez.proposals"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":property"))
}
