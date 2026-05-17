package com.Aien.appblocker

import android.content.Context
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.FileInputStream

@Database(entities = [NfcCard::class, BlockedApp::class, BlockedSchedule::class, AppGroup::class, BlockedWebsite::class, FocusWhitelist::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppBlockerDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private var currentDbPath: String? = null

        private fun isPlainSQLite(file: File): Boolean {
            if (!file.exists()) return false
            return try {
                val bytes = ByteArray(16)
                FileInputStream(file).use { it.read(bytes) }
                val header = String(bytes)
                header.startsWith("SQLite format 3")
            } catch (e: Exception) { false }
        }

        fun get(context: Context): AppDatabase {
            val dbDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AppBlocker")
            val newDbFile = File(dbDir, "appblocker.db")
            val canUseExternal = Environment.isExternalStorageManager()
            
            val expectedPath = if (canUseExternal) newDbFile.absolutePath else "appblocker.db"

            if (INSTANCE != null && currentDbPath != expectedPath) {
                Log.d("AppBlocker", "Wykryto zmianę uprawnień. Przełączanie bazy na: $expectedPath")
                INSTANCE?.close()
                INSTANCE = null
            }

            return INSTANCE ?: synchronized(this) {
                currentDbPath = expectedPath
                
                try {
                    System.loadLibrary("sqlcipher")
                } catch (e: Exception) {
                    Log.e("AppBlocker", "Błąd ładowania libsqlcipher")
                }

                if (canUseExternal) {
                    if (!dbDir.exists()) dbDir.mkdirs()
                    if (newDbFile.exists() && isPlainSQLite(newDbFile)) {
                        Log.w("AppBlocker", "Usuwanie starej nieszyfrowanej bazy z Documents")
                        newDbFile.delete()
                    }
                }

                val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "default"
                val secretSalt = String(byteArrayOf(75, 101, 113, 105, 110, 57))
                val factory = try {
                    net.zetetic.database.sqlcipher.SupportOpenHelperFactory((androidId + secretSalt).toByteArray())
                } catch (e: Exception) { null }

                val builder = Room.databaseBuilder(context, AppDatabase::class.java, expectedPath)
                if (factory != null) builder.openHelperFactory(factory)
                
                builder.allowMainThreadQueries()
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}