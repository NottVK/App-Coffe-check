package co.agrovision.core.database.di

import android.content.Context
import androidx.room.Room
import co.agrovision.core.database.AgroVisionDatabase
import co.agrovision.core.database.AgroVisionMigrations
import co.agrovision.core.database.dao.CaptureDao
import co.agrovision.core.database.dao.PestDao
import co.agrovision.core.database.dao.ReviewDao
import co.agrovision.core.database.dao.SyncCursorDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AgroVisionDatabase =
        Room.databaseBuilder(context, AgroVisionDatabase::class.java, AgroVisionDatabase.NAME)
            .addMigrations(*AgroVisionMigrations.ALL)
            // Nótese lo que NO está aquí: ninguna estrategia de respaldo
            // destructiva. Si Room no encuentra ruta de migración, la app falla
            // al abrir la base. Es lo correcto: un fallo ruidoso en desarrollo
            // en lugar de la pérdida silenciosa, en campo, de capturas que el
            // productor tomó sin señal (§23, error nº 2).
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun provideCaptureDao(database: AgroVisionDatabase): CaptureDao = database.captureDao()

    @Provides
    fun providePestDao(database: AgroVisionDatabase): PestDao = database.pestDao()

    @Provides
    fun provideReviewDao(database: AgroVisionDatabase): ReviewDao = database.reviewDao()

    @Provides
    fun provideSyncCursorDao(database: AgroVisionDatabase): SyncCursorDao = database.syncCursorDao()
}
