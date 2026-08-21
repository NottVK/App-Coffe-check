plugins {
    alias(libs.plugins.agrovision.android.feature)
}

android {
    namespace = "co.agrovision.feature.catalog"
}

dependencies {
    implementation(libs.androidx.compose.material.icons.extended)
}
