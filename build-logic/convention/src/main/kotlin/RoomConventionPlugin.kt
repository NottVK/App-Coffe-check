import co.agrovision.buildlogic.libs
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Room con exportación de esquema obligatoria.
 *
 * Sin los JSON de `schemas/` no existe `MigrationTestHelper`, y sin
 * `MigrationTestHelper` no hay forma de probar una migración. Ese es el camino
 * por el que alguien acaba escribiendo `fallbackToDestructiveMigration` — el
 * error prohibido nº 2 (§23), que borra los datos locales del productor.
 */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        extensions.configure<KspExtension> {
            arg("room.schemaLocation", "${projectDir}/schemas")
            arg("room.generateKotlin", "true")
        }

        dependencies {
            add("implementation", libs.findLibrary("androidx-room-runtime").get())
            add("implementation", libs.findLibrary("androidx-room-ktx").get())
            add("ksp", libs.findLibrary("androidx-room-compiler").get())
            add("androidTestImplementation", libs.findLibrary("androidx-room-testing").get())
        }
    }
}
