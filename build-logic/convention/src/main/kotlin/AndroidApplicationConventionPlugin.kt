import co.agrovision.buildlogic.configureKotlinAndroid
import co.agrovision.buildlogic.libs
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("de.mannodermaus.android-junit5")
        // El contrato de identidad Play Store se engancha aquí, no se recuerda.
        pluginManager.apply("agrovision.published.identity")

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            defaultConfig.targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        dependencies {
            add("coreLibraryDesugaring", libs.findLibrary("desugar-jdk-libs").get())
            add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
            add("testImplementation", libs.findLibrary("junit5-api").get())
            add("testRuntimeOnly", libs.findLibrary("junit5-engine").get())
        }
    }
}
