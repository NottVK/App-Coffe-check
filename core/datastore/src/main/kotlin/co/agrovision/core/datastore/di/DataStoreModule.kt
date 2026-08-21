package co.agrovision.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.datastore.ModelSlotPointer
import co.agrovision.core.datastore.ModelSlotPointerSerializer
import co.agrovision.core.datastore.PreferencesStore
import co.agrovision.core.model.port.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
        @Dispatcher(AgroDispatcher.IO) io: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // Ante corrupción se vuelve a vacío en lugar de lanzar. Perder las
        // preferencias es molesto; que la app no arranque, mucho peor — y el
        // usuario no tiene forma de arreglarlo salvo reinstalar y perderlo todo.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(SupervisorJob() + io),
        produceFile = { File(context.filesDir, "datastore/$PREFERENCES_FILE") },
    )

    /**
     * Puntero de ranura de modelo, con serializador tipado.
     *
     * ⚠️ Ante corrupción se restablece a la ranura A **vacía**, no a la última
     * conocida: un puntero ilegible no puede degradar a «activa la ranura A» si
     * la A pudiera no tener modelo instalado. El repositorio comprueba después
     * qué hay realmente en disco antes de intentar usarlo.
     */
    @Provides
    @Singleton
    fun provideModelSlotDataStore(
        @ApplicationContext context: Context,
        @Dispatcher(AgroDispatcher.IO) io: CoroutineDispatcher,
    ): DataStore<ModelSlotPointer> = DataStoreFactory.create(
        serializer = ModelSlotPointerSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { ModelSlotPointer() },
        scope = CoroutineScope(SupervisorJob() + io),
        produceFile = { File(context.filesDir, "datastore/$MODEL_SLOT_FILE") },
    )

    private const val PREFERENCES_FILE = "agrovision.preferences_pb"
    private const val MODEL_SLOT_FILE = "model_slot.json"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreBindings {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: PreferencesStore): SettingsRepository
}
