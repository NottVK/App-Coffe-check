plugins {
    alias(libs.plugins.agrovision.android.feature)
}

android {
    namespace = "co.agrovision.feature.scan"
}

dependencies {
    // Para `rememberLauncherForActivityResult`: el permiso de cámara se pide
    // aquí, al entrar a la pantalla, no al arrancar la app.
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material.icons.extended)
}
