package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordingEntity::class,
        AudioAssetEntity::class,
        AudioDerivationEntity::class,
        LibraryMetaEntity::class,
        MigrationDiagnosticEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VoicaDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        const val DATABASE_NAME = "voica-recordings.db"

        fun create(context: Context): VoicaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VoicaDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
