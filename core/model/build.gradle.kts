plugins {
    alias(libs.plugins.agrovision.jvm.library)
}

// El dominio. Kotlin puro: sin Android, sin Room, sin LiteRT, sin Retrofit.
//
// Esta lista de dependencias es corta a propósito, y mantenerla corta es el
// trabajo. En el momento en que aquí aparezca `com.android.*` o `androidx.*`,
// el dominio deja de ser dominio y las pruebas dejan de correr en la JVM.
dependencies {
    api(projects.core.common)
    implementation(libs.javax.inject)
}
