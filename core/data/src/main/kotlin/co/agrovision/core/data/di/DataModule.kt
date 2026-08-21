package co.agrovision.core.data.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.common.UuidV7
import co.agrovision.core.data.repository.AccountRepositoryImpl
import co.agrovision.core.data.repository.CaptureRepositoryImpl
import co.agrovision.core.data.repository.LocationProviderImpl
import co.agrovision.core.data.repository.ModelRegistryRepositoryImpl
import co.agrovision.core.data.repository.PestCatalogRepositoryImpl
import co.agrovision.core.data.repository.ReviewRequestRepositoryImpl
import co.agrovision.core.data.repository.TelemetryRecorderImpl
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.ActiveModelProvider
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.ConnectivityMonitor
import co.agrovision.core.model.port.ConnectivityStatus
import co.agrovision.core.model.port.IdGenerator
import co.agrovision.core.model.port.ImageStore
import co.agrovision.core.model.port.LocationProvider
import co.agrovision.core.model.port.ModelRegistryRepository
import co.agrovision.core.model.port.PestCatalogRepository
import co.agrovision.core.model.port.ReviewRequestRepository
import co.agrovision.core.model.port.TelemetryRecorder
import co.agrovision.core.storage.AndroidImageStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {

    @Binds
    @Singleton
    abstract fun bindCaptureRepository(impl: CaptureRepositoryImpl): CaptureRepository

    @Binds
    @Singleton
    abstract fun bindImageStore(impl: AndroidImageStore): ImageStore

    @Binds
    @Singleton
    abstract fun bindPestCatalogRepository(
        impl: PestCatalogRepositoryImpl,
    ): PestCatalogRepository

    @Binds
    @Singleton
    abstract fun bindReviewRequestRepository(
        impl: ReviewRequestRepositoryImpl,
    ): ReviewRequestRepository

    @Binds
    @Singleton
    abstract fun bindAccountRepository(
        impl: AccountRepositoryImpl,
    ): AccountRepository

    @Binds
    @Singleton
    abstract fun bindLocationProvider(
        impl: LocationProviderImpl,
    ): LocationProvider

    @Binds
    @Singleton
    abstract fun bindTelemetryRecorder(
        impl: TelemetryRecorderImpl,
    ): TelemetryRecorder

    @Binds
    @Singleton
    abstract fun bindModelRegistryRepository(
        impl: ModelRegistryRepositoryImpl,
    ): ModelRegistryRepository

    /**
     * `ModelRegistryRepositoryImpl` cumple DOS puertos: el del registro y el de
     * entrega del modelo activo a la capa de inferencia. Es la misma pieza vista
     * desde dos sitios —quién administra las ranuras y quién lee la activa— y
     * separarla obligaría a duplicar la lectura del puntero.
     */
    @Binds
    @Singleton
    abstract fun bindActiveModelProvider(
        impl: ModelRegistryRepositoryImpl,
    ): ActiveModelProvider
}

@Module
@InstallIn(SingletonComponent::class)
object SystemPortsModule {

    /** Reloj real. En pruebas se sustituye por uno fijo: cero pruebas
     *  dependientes de la hora del sistema (§18.1). */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun provideIdGenerator(clock: Clock): IdGenerator =
        IdGenerator { UuidV7.generateString(clock.nowEpochMillis()) }

    @Provides
    @Dispatcher(AgroDispatcher.IO)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(AgroDispatcher.Default)
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Dispatcher(AgroDispatcher.Main)
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    /**
     * Despachador de **un solo hilo** para la inferencia (§7.3).
     *
     * `@Singleton` es obligatorio: dos instancias serían dos hilos, y el
     * intérprete de LiteRT no es seguro para uso concurrente. El fallo no sería
     * una excepción de Kotlin sino un cierre en código nativo sin traza.
     *
     * El hilo se marca como demonio para que no impida que el proceso termine.
     */
    @Provides
    @Singleton
    @Dispatcher(AgroDispatcher.Inference)
    fun provideInferenceDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agrovision-inference").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    @Provides
    @Singleton
    fun provideConnectivityMonitor(
        @ApplicationContext context: Context,
    ): ConnectivityMonitor = AndroidConnectivityMonitor(context)
}

/**
 * Estado de red.
 *
 * Distinguir **medida** de **no medida** no es un detalle: es lo que sostiene
 * ADR-008. En zona rural la única conectividad suele ser datos móviles prepago, y
 * la decisión de gastarlos le corresponde al productor, no a la app.
 */
private class AndroidConnectivityMonitor(
    private val context: Context,
) : ConnectivityMonitor {

    private val manager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<ConnectivityStatus> = callbackFlow {
        val connectivity = manager
        if (connectivity == null) {
            trySend(ConnectivityStatus(isOnline = false, isMetered = true))
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = Unit.also { trySend(current()) }
        }

        trySend(current())
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    override fun current(): ConnectivityStatus {
        val connectivity = manager ?: return OFFLINE
        val capabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        } else {
            null
        } ?: return legacyStatus(connectivity)

        val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return ConnectivityStatus(
            isOnline = online,
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    /** API 22 no tiene `NetworkCapabilities` utilizable (ADR-012: minSdk 22). */
    @Suppress("DEPRECATION")
    private fun legacyStatus(connectivity: ConnectivityManager): ConnectivityStatus {
        val info = connectivity.activeNetworkInfo ?: return OFFLINE
        return ConnectivityStatus(
            isOnline = info.isConnected,
            isMetered = connectivity.isActiveNetworkMetered,
        )
    }

    private companion object {
        val OFFLINE = ConnectivityStatus(isOnline = false, isMetered = true)
    }
}
