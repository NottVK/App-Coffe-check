plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.hilt)
}

android {
    namespace = "co.agrovision.core.storage"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.androidx.core.ktx)
    // Se usa para BORRAR los metadatos EXIF, no para leerlos (§12.2).
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
