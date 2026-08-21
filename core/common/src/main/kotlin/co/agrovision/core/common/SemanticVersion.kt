package co.agrovision.core.common

/**
 * Comparación SemVer para negociar `minAppVersion` con el registro de modelos
 * (§8.2).
 *
 * Comparar versiones como cadenas es el error clásico: `"1.10.0" < "1.9.0"` es
 * cierto lexicográficamente y falso en la realidad. Aquí eso significaría
 * entregar un modelo incompatible a la flota y romper el escaneo en campo.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int = when {
        major != other.major -> major.compareTo(other.major)
        minor != other.minor -> minor.compareTo(other.minor)
        else -> patch.compareTo(other.patch)
    }

    override fun toString() = "$major.$minor.$patch"

    companion object {
        val ZERO = SemanticVersion(0, 0, 0)

        /**
         * Tolerante con sufijos (`1.4.0-rc1`, `1.4.0+build7`) y con versiones
         * incompletas (`1.4`). Devuelve null solo si no hay ni un número mayor
         * reconocible: un manifiesto con versión ilegible no debe activarse.
         */
        fun parseOrNull(raw: String): SemanticVersion? {
            val core = raw.trim().substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            return SemanticVersion(
                major = major,
                minor = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                patch = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            )
        }
    }
}
