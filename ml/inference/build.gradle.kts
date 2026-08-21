plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.hilt)
    // ⚠️ Zona aislada: prohibida toda dependencia de red (ADR-004).
    alias(libs.plugins.agrovision.ml.isolation)
}

android {
    namespace = "co.agrovision.ml.inference"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    // `api`: las implementaciones de las tres compuertas se exponen al grafo de
    // Hilt desde aquí, así que quien dependa de este módulo tiene que verlas.
    api(projects.ml.preprocessing)
    api(projects.ml.ood)

    // Único artefacto externo permitido aquí, junto con la biblioteca estándar.
    // LiteRT es el nombre actual del runtime antes llamado TensorFlow Lite; las
    // clases siguen en el paquete org.tensorflow.lite.
    implementation(libs.litert)
    implementation(libs.litert.gpu)
}
