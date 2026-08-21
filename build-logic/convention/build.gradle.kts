plugins {
    `kotlin-dsl`
}

group = "co.agrovision.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kotlin.composeCompilerGradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "agrovision.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "agrovision.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "agrovision.android.compose"
            implementationClass = "ComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "agrovision.android.feature"
            implementationClass = "FeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "agrovision.android.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("androidRoom") {
            id = "agrovision.android.room"
            implementationClass = "RoomConventionPlugin"
        }
        register("jvmLibrary") {
            id = "agrovision.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        // ⚠️ Segunda de las tres barreras de aislamiento de red (§12.3).
        register("mlIsolation") {
            id = "agrovision.ml.isolation"
            implementationClass = "MlIsolationConventionPlugin"
        }
        // ⚠️ Contrato de identidad Play Store (§2.4).
        register("publishedIdentity") {
            id = "agrovision.published.identity"
            implementationClass = "PublishedIdentityConventionPlugin"
        }
    }
}
