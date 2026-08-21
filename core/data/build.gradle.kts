plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "co.agrovision.core.data"
}

// Implementaciones de los puertos del dominio (ADR-015).
//
// Nótese que NINGÚN `:feature:*` depende de este módulo: los features solo ven
// las interfaces de `:core:model`. Eso es lo que hace estructuralmente imposible
// que una pantalla llame a la red.
dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.storage)
    // Este módulo es donde se COMPONEN las piezas para satisfacer los puertos del
    // dominio, así que ve casi todo. Lo que importa es la dirección: nadie
    // depende de él salvo :app, y ningún :feature:* lo conoce.
    implementation(projects.ml.registry)
    // Nótese que NO hay dependencia de :network:api. La red entra por el puerto
    // `SyncGateway` de :core:model, así que aquí no se ve Retrofit ni OkHttp.

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)
}
