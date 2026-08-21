import co.agrovision.buildlogic.VerifyMlIsolationTask
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * ⚠️ Segunda de las tres barreras de aislamiento de red (§12.3 CLAUDE.md, ADR-004).
 *
 * La primera barrera es el grafo de dependencias: los módulos de inferencia
 * declaran únicamente LiteRT y la biblioteca estándar de Kotlin, de modo que una
 * llamada de red ni siquiera compila.
 *
 * Esta barrera cubre el hueco que deja la primera: una dependencia de red
 * introducida *transitivamente*, sin que nadie la haya escrito en un
 * `build.gradle.kts`. Basta con que alguien añada una utilidad que arrastre
 * OkHttp para que el classpath deje de estar aislado sin que se note.
 *
 * La tercera barrera es la regla de Lint sobre `java.net.*` y `android.net.*`.
 *
 * Por qué tres y no una: el aislamiento del subsistema de inferencia es la
 * frontera más importante del sistema (§5 del documento de arquitectura), y una
 * sola barrera es una barrera con un único modo de fallo.
 *
 * ## Nota de implementación
 *
 * Las configuraciones `<variante>RuntimeClasspath` **no existen** cuando se
 * aplica el plugin: AGP las crea más tarde, al materializar las variantes. Por eso
 * se entra por `onVariants` y se toma `variant.runtimeConfiguration` en vez de
 * componer el nombre a mano — que además dejaría de funcionar en cuanto alguien
 * añadiera un `productFlavor`.
 */
class MlIsolationConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        val verifyAll = tasks.register("verifyMlIsolation") {
            group = "verificación"
            description = "Falla si un módulo aislado resuelve un artefacto de red."
        }

        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryAndroidComponentsExtension> {
                onVariants { variant ->
                    // Se recorre el grafo de RESOLUCIÓN, no los artefactos.
                    //
                    // `resolvedArtifacts` obligaría a elegir un `artifactType`, y
                    // AGP publica una decena de variantes por proyecto (aar,
                    // classes, assets, art-profile…), así que la resolución queda
                    // ambigua y falla.
                    //
                    // Además aquí no hacen falta los archivos: la pregunta es
                    // «¿qué componentes entran en el classpath?», y eso lo
                    // responde el grafo sin descargar ni descomprimir nada.
                    val ids = variant.runtimeConfiguration.incoming
                        .resolutionResult
                        .rootComponent
                        .map { root -> root.collectDependencyIds() }

                    val task = tasks.register<VerifyMlIsolationTask>(
                        "verifyMlIsolation${variant.name.replaceFirstChar(Char::uppercase)}"
                    ) {
                        group = "verificación"
                        description =
                            "Verifica que la variante ${variant.name} no resuelva artefactos de red."
                        modulePath.set(target.path)
                        configuration.set(variant.runtimeConfiguration.name)
                        forbiddenTokens.set(FORBIDDEN)
                        dependencyIds.set(ids)
                        report.set(
                            layout.buildDirectory.file("reports/ml-isolation/${variant.name}.txt")
                        )
                    }
                    verifyAll.configure { dependsOn(task) }
                }
            }

            // El aislamiento no es un paso extra de CI que alguien recuerda
            // ejecutar: se comprueba en cada compilación del módulo. Una
            // verificación que hay que acordarse de lanzar es una verificación
            // que no existe.
            //
            // `matching` en lugar de `named` porque `preBuild` tampoco está
            // garantizado en este punto del ciclo de configuración.
            tasks.matching { it.name == "preBuild" }.configureEach {
                dependsOn(verifyAll)
            }
        }
    }

    private companion object {
        /**
         * Recorre el grafo completo de dependencias, incluidas las transitivas —
         * que son justo las que esta barrera existe para atrapar.
         *
         * El conjunto `visitados` no es una optimización: sin él, un ciclo en el
         * grafo produce recursión infinita, y los grafos de Gradle sí pueden
         * tener ciclos entre componentes.
         */
        fun ResolvedComponentResult.collectDependencyIds(): List<String> {
            val visited = LinkedHashSet<String>()

            fun walk(component: ResolvedComponentResult) {
                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { dependency ->
                        val selected = dependency.selected
                        if (visited.add(selected.id.displayName)) walk(selected)
                    }
            }

            walk(this)
            return visited.toList()
        }

        /**
         * Coincidencia por subcadena sobre el identificador del componente
         * (`grupo:artefacto:versión`). Deliberadamente amplia: es preferible un
         * falso positivo que se resuelve con una línea documentada a un falso
         * negativo que abre en silencio una ruta de red dentro del subsistema de
         * inferencia.
         */
        val FORBIDDEN = listOf(
            "okhttp",
            "retrofit",
            "ktor-client",
            "grpc",
            "volley",
            "apache.httpcomponents",
            "httpclient",
            "firebase",
            "play-services",
            "androidx.work",
        )
    }
}
