import co.agrovision.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Un módulo `:feature:*` es siempre lo mismo: Compose + Hilt + navegación +
 * dominio + sistema de diseño. Repetir esas siete líneas en cinco `build.gradle.kts`
 * es el camino corto a que uno de ellos se desincronice.
 *
 * Obsérvese qué NO se añade aquí: `:network:api`. Una pantalla no habla con la
 * red. Escribe en Room y observa Room (§9.1). Si un feature necesitara Retrofit,
 * el diseño está mal, no el grafo de dependencias.
 */
class FeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("agrovision.android.library")
        pluginManager.apply("agrovision.android.compose")
        pluginManager.apply("agrovision.android.hilt")

        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:common"))
            add("implementation", project(":core:designsystem"))

            add("implementation", libs.findLibrary("androidx-core-ktx").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-navigation-compose").get())
            add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
            add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())

            add("androidTestImplementation", libs.findLibrary("androidx-test-junit").get())
        }
    }
}
