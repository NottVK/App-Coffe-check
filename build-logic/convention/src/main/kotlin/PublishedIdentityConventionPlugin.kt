import co.agrovision.buildlogic.VerifyPublishedIdentityTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * ⚠️ Contrato de identidad Play Store (§2.4 CLAUDE.md).
 *
 * La aplicación ya existe en Google Play. Este no es un proyecto nuevo, es un
 * refactor sobre una base publicada, y hay una lista corta de cosas que, si se
 * rompen, o hacen que Play rechace la subida o hacen que los usuarios pierdan
 * sus datos. Ninguna de ellas produce un error de compilación por sí sola: son
 * exactamente el tipo de fallo que se descubre después de subir el AAB.
 *
 * Por eso la verificación no es un paso de CI que alguien recuerda: es una tarea
 * de la que `bundleRelease` depende. No hay forma de producir un artefacto
 * publicable sin pasar por ella.
 *
 * Dos niveles de rigor, deliberados:
 *  · `debug`   → ADVIERTE. Nadie debería quedarse sin poder compilar en local
 *                porque todavía no ha rellenado PRESERVE.md.
 *  · `release` → FALLA. Nada sale hacia Play sin el contrato completo.
 */
class PublishedIdentityConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        val preserve = rootProject.layout.projectDirectory.file("PRESERVE.md")
        val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")

        // Todo el código Kotlin del repositorio: `fallbackToDestructiveMigration`
        // puede aparecer en cualquier módulo, no solo en :app.
        val kotlinSources = rootProject.fileTree(rootProject.projectDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**", "**/build-logic/**")
        }

        fun register(name: String, strictMode: Boolean, description: String) =
            tasks.register<VerifyPublishedIdentityTask>(name) {
                group = "verificación"
                this.description = description
                preserveFile.set(preserve)
                manifestFile.set(manifest)
                sources.setFrom(kotlinSources)
                declaredApplicationId.set(
                    providers.gradleProperty("agrovision.applicationId")
                )
                declaredVersionCode.set(
                    providers.gradleProperty("agrovision.versionCode").orElse("")
                )
                strict.set(strictMode)
                report.set(layout.buildDirectory.file("reports/published-identity/$name.txt"))
            }

        val strictTask = register(
            name = "verifyPublishedIdentity",
            strictMode = true,
            description = "Falla si el refactor rompe la identidad publicada en Play Store.",
        )
        val lenientTask = register(
            name = "checkPublishedIdentity",
            strictMode = false,
            description = "Advierte sobre desviaciones del contrato de identidad publicada.",
        )

        tasks.matching { it.name in RELEASE_TASKS }.configureEach {
            dependsOn(strictTask)
        }
        tasks.named("preBuild").configure { dependsOn(lenientTask) }
    }

    private companion object {
        val RELEASE_TASKS = setOf("bundleRelease", "assembleRelease", "packageRelease")
    }
}
