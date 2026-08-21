plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "co.agrovision.network.api"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.okhttp.logging)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit4)
}
