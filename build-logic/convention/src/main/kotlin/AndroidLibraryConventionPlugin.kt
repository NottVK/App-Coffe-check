import co.agrovision.buildlogic.configureKotlinAndroid
import co.agrovision.buildlogic.libs
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("de.mannodermaus.android-junit5")

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            defaultConfig.targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            testOptions.unitTests.isIncludeAndroidResources = true
            // Los métodos del framework de Android devuelven su valor por
            // defecto en vez de lanzar. Sin esto, una simple llamada a
            // `android.util.Log` dentro del código bajo prueba revienta —y si
            // ocurre dentro de una corrutina, la excepción se traga y la prueba
            // falla con un estado inesperado en lugar de con la causa.
            testOptions.unitTests.isReturnDefaultValues = true
            // Las reglas de conservación de R8 viven en :app: una sola fuente
            // de verdad evita reglas huérfanas que nadie sabe si siguen vigentes.
        }

        dependencies {
            add("coreLibraryDesugaring", libs.findLibrary("desugar-jdk-libs").get())
            add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
            add("testImplementation", libs.findLibrary("junit5-api").get())
            add("testImplementation", libs.findLibrary("junit5-params").get())
            add("testRuntimeOnly", libs.findLibrary("junit5-engine").get())
            // Ejecuta las clases JUnit4 —Robolectric las exige— sobre la
            // plataforma JUnit5. Sin esto se saltan sin avisar.
            add("testRuntimeOnly", libs.findLibrary("junit5-vintage").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            add("testImplementation", libs.findLibrary("mockk").get())
        }
    }
}
