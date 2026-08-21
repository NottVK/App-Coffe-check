package co.agrovision.core.common

/**
 * Retorno de todo caso de uso (§3.3: «sin excepciones para reglas de negocio»).
 *
 * Una excepción es un canal de control invisible en la firma: quien llama no
 * sabe qué puede fallar hasta que falla en producción. Aquí el conjunto de
 * fallos posibles es parte del tipo, y el compilador obliga a decidir qué hacer
 * con cada uno.
 *
 * Las excepciones se reservan para fallos de infraestructura —disco lleno, base
 * de datos corrupta, memoria nativa agotada— donde no hay decisión de negocio
 * que tomar.
 */
sealed interface AgroResult<out T, out E> {

    @JvmInline
    value class Success<out T>(val value: T) : AgroResult<T, Nothing>

    @JvmInline
    value class Failure<out E>(val error: E) : AgroResult<Nothing, E>

    val isSuccess: Boolean get() = this is Success

    companion object {
        fun <T> success(value: T): AgroResult<T, Nothing> = Success(value)
        fun <E> failure(error: E): AgroResult<Nothing, E> = Failure(error)
        val unit: AgroResult<Unit, Nothing> = Success(Unit)
    }
}

inline fun <T, E, R> AgroResult<T, E>.map(transform: (T) -> R): AgroResult<R, E> = when (this) {
    is AgroResult.Success -> AgroResult.Success(transform(value))
    is AgroResult.Failure -> this
}

inline fun <T, E, R> AgroResult<T, E>.flatMap(
    transform: (T) -> AgroResult<R, E>,
): AgroResult<R, E> = when (this) {
    is AgroResult.Success -> transform(value)
    is AgroResult.Failure -> this
}

inline fun <T, E, F> AgroResult<T, E>.mapError(transform: (E) -> F): AgroResult<T, F> = when (this) {
    is AgroResult.Success -> this
    is AgroResult.Failure -> AgroResult.Failure(transform(error))
}

inline fun <T, E, R> AgroResult<T, E>.fold(
    onSuccess: (T) -> R,
    onFailure: (E) -> R,
): R = when (this) {
    is AgroResult.Success -> onSuccess(value)
    is AgroResult.Failure -> onFailure(error)
}

inline fun <T, E> AgroResult<T, E>.onSuccess(action: (T) -> Unit): AgroResult<T, E> = apply {
    if (this is AgroResult.Success) action(value)
}

inline fun <T, E> AgroResult<T, E>.onFailure(action: (E) -> Unit): AgroResult<T, E> = apply {
    if (this is AgroResult.Failure) action(error)
}

fun <T, E> AgroResult<T, E>.getOrNull(): T? = (this as? AgroResult.Success)?.value

fun <T, E> AgroResult<T, E>.errorOrNull(): E? = (this as? AgroResult.Failure)?.error

fun <T, E> AgroResult<T, E>.getOrElse(fallback: (E) -> @UnsafeVariance T): T = when (this) {
    is AgroResult.Success -> value
    is AgroResult.Failure -> fallback(error)
}

/**
 * Frontera con la infraestructura. Una excepción de disco o de red se convierte
 * aquí en un fallo tipado; a partir de este punto el resto del código ya no
 * necesita `try`.
 */
inline fun <T, E> runCatchingDomain(
    onThrow: (Throwable) -> E,
    block: () -> T,
): AgroResult<T, E> = try {
    AgroResult.Success(block())
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    // Nunca capturar la cancelación de una corrutina: convertirla en «fallo»
    // rompe la cancelación estructurada y deja trabajo huérfano corriendo.
    throw cancellation
} catch (throwable: Throwable) {
    AgroResult.Failure(onThrow(throwable))
}
