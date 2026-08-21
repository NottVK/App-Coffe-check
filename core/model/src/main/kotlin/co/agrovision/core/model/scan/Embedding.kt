package co.agrovision.core.model.scan

/**
 * Vector de la penúltima capa, con igualdad estructural.
 *
 * `FloatArray` compara por identidad. Un `data class` que lo contenga en crudo
 * produce dos entidades «distintas» con los mismos datos, y el resultado es una
 * rejilla de historial que se recompone entera en cada emisión de Room.
 * Envolverlo cuesta una clase y elimina esa categoría de fallo.
 */
class Embedding(val values: FloatArray) {

    val dimension: Int get() = values.size

    operator fun get(index: Int): Float = values[index]

    override fun equals(other: Any?): Boolean =
        this === other || (other is Embedding && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = "Embedding(dim=$dimension)"

    companion object {
        fun of(vararg values: Float) = Embedding(values)
    }
}
