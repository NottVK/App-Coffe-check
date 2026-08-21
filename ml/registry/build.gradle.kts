plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "co.agrovision.ml.registry"
}

// Este módulo NO está en la zona aislada, pero tampoco tiene dependencias de red:
// la descarga entra por el puerto `ArtifactFetcher`, implementado en :network:api.
//
// La separación importa por una razón concreta: la verificación de firma y de
// hash se puede probar entera en la JVM, sin servidor y sin emulador, con
// artefactos sintéticos. Un verificador criptográfico que solo se puede probar
// contra un servidor real es un verificador que no se prueba.
dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    // `api` y no `implementation`: `SmokeTestRunner` aparece en el constructor
    // público de `ArtifactInstaller`, así que forma parte de la superficie de
    // este módulo y Hilt necesita verlo desde fuera.
    api(projects.ml.inference)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
    implementation(libs.androidx.core.ktx)

    // Robolectric porque `android.util.Base64` es la única API de Android que
    // toca el verificador de firma, y esa prueba tiene que correr en la JVM.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)
}
