import co.agrovision.buildlogic.configureJUnitPlatform
import co.agrovision.buildlogic.configureKotlinJvm
import co.agrovision.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Módulo Kotlin puro. No aplica el plugin de Android a propósito: si `:core:model`
 * pudiera importar `android.*`, el dominio dejaría de ser dominio y la prueba
 * unitaria dejaría de correr en la JVM.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()
        configureJUnitPlatform()

        dependencies {
            add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
            add("testImplementation", libs.findLibrary("junit5-api").get())
            add("testImplementation", libs.findLibrary("junit5-params").get())
            add("testRuntimeOnly", libs.findLibrary("junit5-engine").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            add("testImplementation", libs.findLibrary("mockk").get())
        }
    }
}
