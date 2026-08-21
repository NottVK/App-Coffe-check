plugins {
    alias(libs.plugins.agrovision.android.library)
    // ⚠️ Zona aislada: prohibida toda dependencia de red (ADR-004).
    alias(libs.plugins.agrovision.ml.isolation)
}

android {
    namespace = "co.agrovision.ml.ood"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
}
