plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "co.agrovision.core.datastore"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
}
