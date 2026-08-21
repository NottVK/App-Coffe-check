package co.agrovision.network.api

// El paquete lleva el prefijo `com.jakewharton`: es el conversor de Jake Wharton,
// no el oficial de Square. Se distinguen precisamente en eso, y el import sin
// prefijo compila en la cabeza de uno pero no en el compilador.
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PublicClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        // No enviar `"campo": null` en cada captura. Sobre datos prepago y con
        // lotes de quince, esos bytes los paga el productor.
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Anclaje de certificado (§15.4).
     *
     * ⚠️ **Un solo pin convierte la rotación del certificado en un apagón total de
     * la flota instalada.** Cuando el certificado caduca o se reemplaza, cada
     * teléfono con la app deja de poder hablar con el servidor, y la única
     * solución es que todos actualicen desde Play — cosa que en zona rural con
     * datos prepago no ocurre en semanas.
     *
     * Por eso `agrovision.pinning.pins` exige dos pines: el actual y el de
     * respaldo, cuya clave ya está generada y guardada aunque el certificado
     * todavía no se haya emitido.
     *
     * Si no hay pines configurados, se devuelve `CertificatePinner.DEFAULT` —sin
     * anclaje— en lugar de anclar a un valor inventado, que dejaría la app sin
     * red desde el primer arranque.
     */
    @Provides
    @Singleton
    fun provideCertificatePinner(
        @Named("pinnedHosts") hosts: String,
        @Named("pins") pins: String,
    ): CertificatePinner {
        val hostList = hosts.split(',').map(String::trim).filter { it.isNotEmpty() }
        val pinList = pins.split(',')
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith(PLACEHOLDER) }

        if (hostList.isEmpty() || pinList.isEmpty()) return CertificatePinner.DEFAULT

        return CertificatePinner.Builder().apply {
            hostList.forEach { host ->
                pinList.forEach { pin -> add(host, "sha256/$pin") }
            }
        }.build()
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        pinner: CertificatePinner,
        deviceIdInterceptor: DeviceIdInterceptor,
        integrityInterceptor: PlayIntegrityInterceptor,
        authInterceptor: AuthInterceptor,
        @Named("loggingInterceptor") logging: Interceptor?,
    ): OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(pinner)
        // Tiempos generosos: 3G en ladera no es una red rápida, es una red lenta
        // que funciona. Cortar a los 10 s convierte una sincronización viable en
        // un reintento infinito.
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // El reintento de OkHttp se desactiva: los reintentos los gobierna
        // SyncPendingUseCase con backoff exponencial y persistencia. Dos
        // mecanismos de reintento superpuestos multiplican el consumo de datos
        // sin mejorar la tasa de éxito.
        .retryOnConnectionFailure(false)
        .addInterceptor(deviceIdInterceptor)
        .addInterceptor(integrityInterceptor)
        .addInterceptor(authInterceptor)
        .apply { logging?.let(::addInterceptor) }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json,
        @Named("apiBaseUrl") baseUrl: String,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): AgroVisionApiService =
        retrofit.create(AgroVisionApiService::class.java)

    @Provides
    @Singleton
    fun providePublicApiService(retrofit: Retrofit): PublicApiService =
        retrofit.create(PublicApiService::class.java)

    private const val JSON_MEDIA_TYPE = "application/json"
    private const val PLACEHOLDER = "TODO"
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val WRITE_TIMEOUT_SECONDS = 120L
    private const val CALL_TIMEOUT_SECONDS = 300L
}
