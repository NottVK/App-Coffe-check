// Importado explícitamente: dentro de un script Kotlin DSL, `java` se resuelve a
// la extensión `JavaPluginExtension` del proyecto y tapa el paquete `java.*`.
// Sin este import, `java.util.Properties()` no compila.
import java.util.Properties

plugins {
    alias(libs.plugins.agrovision.android.application)
    alias(libs.plugins.agrovision.android.compose)
    alias(libs.plugins.agrovision.android.hilt)
}

android {
    // ⚠️ `namespace` y `applicationId` son deliberadamente DISTINTOS.
    //
    //   namespace     = paquete Kotlin y clase R. Refleja la organización real.
    //   applicationId = identidad ante Google Play. Congelada para siempre.
    //
    // Android permite que difieran, y aprovecharlo aquí es lo que deja el código
    // con un paquete decente sin romper la continuidad de la app publicada
    // (PRESERVE.md §1).
    namespace = "co.agrovision"

    defaultConfig {
        applicationId = providers.gradleProperty("agrovision.applicationId").get()
        versionName = providers.gradleProperty("agrovision.versionName").get()
        versionCode = providers.gradleProperty("agrovision.versionCode").get().toInt()

        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${providers.gradleProperty("agrovision.api.baseUrl").get()}\"",
        )
        buildConfigField(
            "String",
            "MODEL_SIGNING_PUBLIC_KEY",
            "\"${providers.gradleProperty("agrovision.model.signingPublicKeyBase64").get()}\"",
        )
        // Hosts anclados y pines viajan separados: el mismo juego de pines
        // aplica a los dos dominios, y mezclarlos en una sola cadena obligaría a
        // parsearla en dos sitios distintos.
        buildConfigField(
            "String",
            "PINNED_HOSTS",
            "\"${providers.gradleProperty("agrovision.pinning.apiHost").get()}," +
                "${providers.gradleProperty("agrovision.pinning.cdnHost").get()}\"",
        )
        buildConfigField(
            "String",
            "PINNED_PINS",
            "\"${providers.gradleProperty("agrovision.pinning.pins").get()}\"",
        )

        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // ⚠️ El keystore NUNCA entra al repositorio (§15.6). Se lee de
            // keystore.properties (gitignored) en local y de secretos de CI en
            // el pipeline.
            //
            // Si el archivo no existe, la configuración queda sin rellenar y
            // `bundleRelease` falla con un mensaje claro en vez de producir un
            // AAB sin firmar que Play rechazará media hora más tarde.
            val properties = rootProject.file("keystore.properties")
            if (properties.exists()) {
                val keystore = Properties().apply {
                    properties.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // El sufijo aísla la instalación de depuración de la publicada: se
            // pueden tener ambas en el mismo teléfono, que es lo que hace falta
            // para verificar la migración desde Capacitor contra datos reales.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // R8 en modo full (§12.7).
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        // LiteRT trae los .so por ABI. Sin esto, el AAB los duplica y el APK
        // resultante se sale del presupuesto de 40 MB (§3.2 RNF).
        jniLibs.useLegacyPackaging = false
    }

    bundle {
        language.enableSplit = false // Español único; dividir no ahorra nada.
        density.enableSplit = true
        abi.enableSplit = true
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.storage)

    implementation(projects.feature.scan)
    implementation(projects.feature.catalog)
    // Pendientes: review, history, settings.
    // Sus rutas ya existen en AgroVisionDestinations; falta la pantalla.

    implementation(projects.ml.inference)
    implementation(projects.ml.ood)
    implementation(projects.ml.preprocessing)
    implementation(projects.ml.registry)
    implementation(projects.network.api)
    implementation(projects.sync.worker)

    // OkHttp llega aquí solo para poder construir el interceptor de registro y
    // los proveedores de configuración. La app no hace peticiones: eso vive
    // entero en :network:api.
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}

// Firebase Cloud Messaging solo si hay configuración. Mantiene el proyecto
// compilable para quien clona sin credenciales, que es el caso de cualquiera
// que llegue nuevo al equipo.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    dependencies {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.messaging)
    }
} else {
    logger.lifecycle(
        "AgroVisión: sin google-services.json — las notificaciones push quedan inactivas."
    )
}
