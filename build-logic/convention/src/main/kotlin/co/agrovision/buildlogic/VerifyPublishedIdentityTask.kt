package co.agrovision.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Valida el árbol de trabajo contra el contrato extraído del AAB publicado.
 *
 * Cada comprobación corresponde a una fila de la tabla de invariantes de §2.1 y
 * a un fallo concreto observado en refactores de aplicaciones ya publicadas.
 */
abstract class VerifyPublishedIdentityTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preserveFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val declaredApplicationId: Property<String>

    @get:Input
    @get:Optional
    abstract val declaredVersionCode: Property<String>

    @get:Input
    abstract val strict: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        val contract = parseContract(preserveFile.get().asFile.readText())
        val manifest = manifestFile.get().asFile.readText()

        checkApplicationId(contract, problems)
        checkVersionCode(contract, problems)
        checkExportedComponents(contract, manifest, problems)
        checkDeepLinks(contract, manifest, problems)
        checkDestructiveMigration(problems)

        writeReport(problems)

        if (problems.isEmpty()) {
            logger.lifecycle("✓ Identidad publicada preservada (${contract.size} claves del contrato verificadas).")
            return
        }

        val message = buildMessage(problems)
        if (strict.get()) throw GradleException(message) else logger.warn(message)
    }

    // ── Comprobaciones ────────────────────────────────────────────────────────

    /**
     * Invariante nº 1 y error prohibido nº 1 (§23): cambiar el `applicationId`
     * no actualiza la aplicación, publica una distinta. Los usuarios actuales
     * se quedan con una app huérfana que nunca vuelve a recibir una versión.
     */
    private fun checkApplicationId(contract: Map<String, List<String>>, problems: MutableList<String>) {
        val declared = declaredApplicationId.get().trim()
        val published = contract.single("applicationId")

        if (declared.isEmpty() || declared.startsWith(TODO_PREFIX)) {
            problems += """
                |`agrovision.applicationId` sigue en «$declared» dentro de gradle.properties.
                |
                |Obtén el valor real y colócalo ahí. Es la única línea del repositorio
                |donde vive, y el paquete Kotlin (namespace) no cambia con él:
                |
                |    Play Console → tu app → Configuración → Detalles de la app
                |    o bien:  aapt2 dump badging app-release.aab | head -1
            """.trimMargin()
            return
        }
        if (published == null || published.startsWith(TODO_PREFIX)) {
            problems += "PRESERVE.md no declara todavía `applicationId`. Complétalo desde el AAB publicado."
            return
        }
        if (declared != published) {
            problems += """
                |El `applicationId` NO coincide con el publicado.
                |    gradle.properties: $declared
                |    PRESERVE.md:       $published
                |Si el cambio es intencionado, no lo es: revisa §2.1 antes de continuar.
            """.trimMargin()
        }
    }

    /**
     * Invariante nº 3: Play rechaza cualquier subida cuyo `versionCode` no supere
     * estrictamente al último publicado, y un `versionCode` reutilizado no se
     * puede liberar nunca más.
     */
    private fun checkVersionCode(contract: Map<String, List<String>>, problems: MutableList<String>) {
        val publishedRaw = contract.single("versionCode")
        val published = publishedRaw?.toIntOrNull()
        if (published == null) {
            problems += "PRESERVE.md no declara un `versionCode` numérico (valor actual: «$publishedRaw»)."
            return
        }
        val declared = declaredVersionCode.orNull?.trim().orEmpty()
        if (declared.isEmpty()) {
            // En local es normal: CI lo calcula y lo pasa con -P. No es un problema.
            logger.info("versionCode no fijado en esta invocación; CI lo calcula (§17.2).")
            return
        }
        val current = declared.toIntOrNull()
        if (current == null) {
            problems += "`agrovision.versionCode`=«$declared» no es un entero."
        } else if (current <= published) {
            problems += "El versionCode $current no supera al publicado ($published). Debe ser estrictamente mayor."
        }
    }

    /**
     * Invariante nº 5: renombrar una clase exportada rompe todo acceso externo a
     * ella — accesos directos del lanzador, integraciones, enlaces guardados —
     * y no produce ningún error visible al compilar.
     */
    private fun checkExportedComponents(
        contract: Map<String, List<String>>,
        manifest: String,
        problems: MutableList<String>,
    ) {
        contract["exported"].orEmpty()
            .filterNot { it.startsWith(TODO_PREFIX) }
            .forEach { name ->
                if (!manifest.contains("android:name=\"$name\"") && !manifest.contains(shortName(name))) {
                    problems += "Falta el componente exportado «$name» declarado en PRESERVE.md."
                }
            }
    }

    /**
     * Invariante nº 6: un App Link que desaparece deja enlaces rotos en mensajes
     * de WhatsApp y en respuestas de agrónomo ya enviadas. Cada uno de esos
     * enlaces es un usuario que aterriza en el navegador en lugar de en la app.
     */
    private fun checkDeepLinks(
        contract: Map<String, List<String>>,
        manifest: String,
        problems: MutableList<String>,
    ) {
        contract["deepLink"].orEmpty()
            .filterNot { it.startsWith(TODO_PREFIX) }
            .forEach { link ->
                val host = link.substringAfter("://").substringBefore('/')
                if (!manifest.contains(host)) {
                    problems += "Falta el enlace profundo «$link» (host «$host») declarado en PRESERVE.md."
                }
            }
    }

    /**
     * Error prohibido nº 2 (§23). `fallbackToDestructiveMigration` borra la base
     * de datos local cuando una migración no encaja. En esta aplicación eso
     * significa perder capturas que el productor tomó sin señal y que todavía no
     * han subido: trabajo de campo irrecuperable.
     */
    private fun checkDestructiveMigration(problems: MutableList<String>) {
        val offenders = sources.files
            .filter { it.isFile }
            .filter { file ->
                file.readText().lineSequence().any { line ->
                    DESTRUCTIVE.any { line.contains(it) } && !line.trimStart().startsWith("//")
                }
            }
        offenders.forEach {
            problems += "Uso prohibido de fallbackToDestructiveMigration en ${it.path} (§23, error nº 2)."
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private fun parseContract(text: String): Map<String, List<String>> {
        val body = text.substringAfter(BEGIN, "").substringBefore(END, "")
        return body.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(':') }
            .map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            .groupBy({ it.first }, { it.second })
    }

    private fun Map<String, List<String>>.single(key: String): String? =
        this[key]?.firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun shortName(fqcn: String) = "android:name=\".${fqcn.substringAfterLast('.')}\""

    private fun writeReport(problems: List<String>) {
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            if (problems.isEmpty()) "OK — identidad publicada preservada.\n"
            else problems.joinToString("\n\n")
        )
    }

    private fun buildMessage(problems: List<String>) = buildString {
        appendLine()
        appendLine("╭──────────────────────────────────────────────────────────────────────╮")
        appendLine("│  CONTRATO DE IDENTIDAD PLAY STORE — ${problems.size} incumplimiento(s)".padEnd(71) + "│")
        appendLine("╰──────────────────────────────────────────────────────────────────────╯")
        appendLine()
        problems.forEachIndexed { index, problem ->
            appendLine("${index + 1}. $problem")
            appendLine()
        }
        appendLine("Contrato: ${preserveFile.get().asFile.path}")
        appendLine("Detalle:  ${report.get().asFile.path}")
        if (strict.get()) {
            appendLine()
            appendLine("Esta comprobación bloquea `bundleRelease` a propósito. Ninguno de estos")
            appendLine("fallos produce un error al compilar: se manifiestan después de subir el AAB.")
        }
    }

    private companion object {
        const val BEGIN = "<!-- CONTRATO-INICIO -->"
        const val END = "<!-- CONTRATO-FIN -->"
        const val TODO_PREFIX = "TODO"
        val DESTRUCTIVE = listOf(
            "fallbackToDestructiveMigration",
            "fallbackToDestructiveMigrationFrom",
            "fallbackToDestructiveMigrationOnDowngrade",
        )
    }
}
