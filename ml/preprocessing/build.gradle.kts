plugins {
    alias(libs.plugins.agrovision.android.library)
    // ⚠️ Zona aislada: prohibida toda dependencia de red (ADR-004).
    alias(libs.plugins.agrovision.ml.isolation)
}

android {
    namespace = "co.agrovision.ml.preprocessing"
}

// La lista de dependencias de este módulo es corta A PROPÓSITO, y mantenerla
// corta es parte del trabajo. `verifyMlIsolation` falla la compilación si algo
// arrastra OkHttp, Retrofit, gRPC o similares, incluso transitivamente.
dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)

    // Solo las anotaciones. No es Hilt ni Dagger: es un jar de anotaciones puro,
    // sin código ejecutable y sin capacidad de abrir un socket, así que no
    // compromete el aislamiento. Permite que estas clases se dejen inyectar sin
    // que el módulo conozca el contenedor.
    implementation(libs.javax.inject)
}
