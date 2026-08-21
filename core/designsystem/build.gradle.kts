plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.compose)
}

android {
    namespace = "co.agrovision.core.designsystem"
}

dependencies {
    api(projects.core.common)
    api(projects.core.model)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
