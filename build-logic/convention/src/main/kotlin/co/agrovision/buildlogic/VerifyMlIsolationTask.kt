package co.agrovision.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyMlIsolationTask : DefaultTask() {

    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val configuration: Property<String>

    @get:Input
    abstract val forbiddenTokens: ListProperty<String>

    @get:Input
    abstract val dependencyIds: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val ids = dependencyIds.get()
        val tokens = forbiddenTokens.get()

        val violations = ids.mapNotNull { id ->
            val normalized = id.lowercase()
            tokens.firstOrNull { normalized.contains(it) }?.let { token -> token to id }
        }

        val out = report.get().asFile
        out.parentFile.mkdirs()

        if (violations.isEmpty()) {
            out.writeText(
                buildString {
                    appendLine("AISLAMIENTO VERIFICADO — ${modulePath.get()} / ${configuration.get()}")
                    appendLine("${ids.size} artefactos resueltos, ninguno de red.")
                    ids.sorted().forEach { appendLine("  · $it") }
                }
            )
            return
        }

        val detail = violations.joinToString("\n") { (token, id) ->
            "  · $id  →  coincide con «$token»"
        }
        out.writeText("VIOLACIÓN\n$detail\n")

        throw GradleException(
            """
            |
            |╭──────────────────────────────────────────────────────────────────────╮
            |│  VIOLACIÓN DE AISLAMIENTO DE RED                                     │
            |╰──────────────────────────────────────────────────────────────────────╯
            |
            |Módulo:        ${modulePath.get()}
            |Configuración: ${configuration.get()}
            |
            |Este módulo pertenece a la zona aislada del subsistema de inferencia y
            |tiene prohibida toda dependencia de red (ADR-004). Se resolvieron estos
            |artefactos:
            |
            |$detail
            |
            |Casi siempre es una dependencia TRANSITIVA que nadie escribió a mano.
            |Para localizar quién la arrastra:
            |
            |    ./gradlew ${modulePath.get()}:dependencies --configuration ${configuration.get()}
            |
            |Y para resolverlo, en orden de preferencia:
            |
            |  1. Elimina la dependencia directa que la arrastra. Si el subsistema de
            |     inferencia la necesita, casi siempre significa que la lógica está en
            |     el módulo equivocado.
            |  2. Excluye el artefacto transitivo:
            |         implementation(libs.algo) { exclude(group = "com.squareup.okhttp3") }
            |  3. Si —y solo si— el artefacto no puede abrir un socket, documéntalo en
            |     un ADR nuevo bajo docs/adr/ y añádelo a la lista de excepciones del
            |     plugin. Nunca en silencio.
            |
            """.trimMargin()
        )
    }
}
