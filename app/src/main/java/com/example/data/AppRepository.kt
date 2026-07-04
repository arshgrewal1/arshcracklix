package com.example.data

import kotlinx.coroutines.flow.Flow

class AppRepository(private val appDao: AppDao) {
    val bookmarks: Flow<List<Bookmark>> = appDao.getAllBookmarks()
    val history: Flow<List<HistoryItem>> = appDao.getRecentHistory()
    val downloads: Flow<List<DownloadedMaterial>> = appDao.getAllDownloads()
    val notes: Flow<List<OfflineNote>> = appDao.getAllNotes()
    val streaks: Flow<List<StudyStreak>> = appDao.getAllStreakLogs()
    val totalStudyTime: Flow<Long?> = appDao.getTotalStudyTime()

    suspend fun insertBookmark(bookmark: Bookmark) = appDao.insertBookmark(bookmark)
    suspend fun deleteBookmarkByUrl(url: String) = appDao.deleteBookmarkByUrl(url)
    suspend fun isBookmarked(url: String): Boolean = appDao.isBookmarked(url)

    suspend fun insertHistory(item: HistoryItem) = appDao.insertHistory(item)
    suspend fun clearHistory() = appDao.clearHistory()

    suspend fun insertDownload(download: DownloadedMaterial) = appDao.insertDownload(download)
    suspend fun deleteDownloadById(id: String) = appDao.deleteDownloadById(id)

    suspend fun insertNote(note: OfflineNote) = appDao.insertNote(note)
    suspend fun deleteNoteById(id: Long) = appDao.deleteNoteById(id)

    suspend fun insertStreakLog(streak: StudyStreak) = appDao.insertStreakLog(streak)
}
