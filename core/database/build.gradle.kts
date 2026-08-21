plugins {
    alias(libs.plugins.agrovision.android.library)
    alias(libs.plugins.agrovision.android.hilt)
    alias(libs.plugins.agrovision.android.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "co.agrovision.core.database"

    defaultConfig {
        // Los JSON de esquema son entrada de las pruebas de migración. Sin ellos
        // `MigrationTestHelper` no puede construir la base en la versión anterior.
        testInstrumentationRunnerArguments["room.schemaLocation"] = "$projectDir/schemas"
    }

    sourceSets.getByName("androidTest") {
        assets.srcDirs(files("$projectDir/schemas"))
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit4)
}
