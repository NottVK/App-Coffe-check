// Raíz sin lógica: los plugins se declaran aquí con `apply false` para fijar su
// versión una sola vez, y cada módulo aplica el plugin de convención que le toca.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.android.junit5) apply false
    alias(libs.plugins.google.services) apply false
}

/**
 * Atajo de conveniencia: ejecuta las tres verificaciones estructurales del
 * proyecto de una sola vez. Es lo que corre CI antes de cualquier otra cosa
 * (§17.3) y lo que conviene ejecutar antes de abrir un PR.
 */
tasks.register("verifyArchitecture") {
    group = "verificación"
    description = "Aislamiento de red de :ml + contrato de identidad Play Store."
    dependsOn(
        ":app:verifyPublishedIdentity",
        ":ml:preprocessing:verifyMlIsolation",
        ":ml:inference:verifyMlIsolation",
        ":ml:ood:verifyMlIsolation",
    )
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
