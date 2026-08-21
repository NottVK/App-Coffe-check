import co.agrovision.buildlogic.libs
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val configure: CommonExtension<*, *, *, *, *, *>.() -> Unit = {
            buildFeatures.compose = true
        }
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryExtension> { configure() }
        }
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> { configure() }
        }

        extensions.configure<ComposeCompilerGradlePluginExtension> {
            // Informes de estabilidad. Sin esto, una recomposición de más en la
            // rejilla del historial se descubre en campo, no en el escritorio.
            val dir = layout.buildDirectory.dir("compose-metrics")
            metricsDestination.set(dir)
            reportsDestination.set(dir)
            stabilityConfigurationFile.set(
                rootProject.layout.projectDirectory.file("config/compose-stability.conf")
            )
        }

        val bom = libs.findLibrary("androidx-compose-bom").get()
        dependencies {
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
            add("implementation", libs.findLibrary("androidx-compose-ui").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("androidx-compose-material3").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
        }
    }
}
