package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- ENTITIES ---

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "history_items")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadedMaterial(
    @PrimaryKey val id: String, // Unique download ID or file hash
    val url: String,
    val title: String,
    val localPath: String,
    val sizeBytes: Long,
    val mimeType: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "notes")
data class OfflineNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val pageUrl: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "streaks")
data class StudyStreak(
    @PrimaryKey val date: String, // "yyyy-MM-dd"
    val durationSeconds: Long,
    val timestamp: Long = System.currentTimeMillis()
)

// --- DAOS ---

@Dao
interface AppDao {
    // Bookmarks
    @Query("SELECT * FROM bookmarks ORDER BY timestamp DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteBookmarkByUrl(url: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    suspend fun isBookmarked(url: String): Boolean

    // History
    @Query("SELECT * FROM history_items ORDER BY timestamp DESC LIMIT 100")
    fun getRecentHistory(): Flow<List<HistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: HistoryItem)

    @Query("DELETE FROM history_items")
    suspend fun clearHistory()

    // Downloads
    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadedMaterial>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadedMaterial)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: String)

    // Notes
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<OfflineNote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: OfflineNote)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Long)

    // Streaks & Study Logs
    @Query("SELECT * FROM streaks ORDER BY date DESC")
    fun getAllStreakLogs(): Flow<List<StudyStreak>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStreakLog(streak: StudyStreak)

    @Query("SELECT SUM(durationSeconds) FROM streaks")
    fun getTotalStudyTime(): Flow<Long?>
}

// --- DATABASE ---

@Database(
    entities = [
        Bookmark::class,
        HistoryItem::class,
        DownloadedMaterial::class,
        OfflineNote::class,
        StudyStreak::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cracklix_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
