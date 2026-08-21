plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.hilt)
}

android {
    namespace = "co.agrovision.sync.worker"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.process)
    ksp(libs.androidx.hilt.compiler)

    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.junit)
}
