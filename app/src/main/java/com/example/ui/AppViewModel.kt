package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = AppRepository(db.appDao())

    // --- Dynamic Settings ---
    private val sharedPrefs = application.getSharedPreferences("cracklix_prefs", Context.MODE_PRIVATE)
    
    private val _baseUrl = MutableStateFlow(
        sharedPrefs.getString("base_url", "https://cracklix.vercel.app") ?: "https://cracklix.vercel.app"
    )
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    // --- UI States ---
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("Cracklix")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0)
    val loadingProgress: StateFlow<Int> = _loadingProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isNetworkAvailable = MutableStateFlow(isInternetAvailable())
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private val _isSecurePage = MutableStateFlow(false)
    val isSecurePage: StateFlow<Boolean> = _isSecurePage.asStateFlow()

    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked.asStateFlow()

    // --- Local Persisted Data ---
    val bookmarks = repository.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = repository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads = repository.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notes = repository.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val streaks = repository.streaks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val totalStudyTime = repository.totalStudyTime.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    init {
        // Log daily streak on app launch
        logTodayStudy()
        
        // Listen to URL changes to check if bookmarked
        viewModelScope.launch {
            _currentUrl.collect { url ->
                if (url.isNotEmpty()) {
                    _isBookmarked.value = repository.isBookmarked(url)
                    checkPageSecurity(url)
                }
            }
        }
    }

    // --- Update Caches & Base URL ---
    fun setBaseUrl(newUrl: String) {
        var formattedUrl = newUrl.trim()
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        _baseUrl.value = formattedUrl
        sharedPrefs.edit().putString("base_url", formattedUrl).apply()
    }

    // --- Network Awareness ---
    fun updateNetworkStatus() {
        _isNetworkAvailable.value = isInternetAvailable()
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    // --- WebView Actions ---
    fun onUrlChanged(url: String) {
        _currentUrl.value = url
        if (url.isNotEmpty() && !url.contains("about:blank")) {
            saveToHistory(url, _pageTitle.value)
        }
    }

    fun onTitleChanged(title: String) {
        _pageTitle.value = title
    }

    fun onProgressChanged(progress: Int) {
        _loadingProgress.value = progress
        _isLoading.value = progress < 100
    }

    // --- Security Interceptor ---
    private fun checkPageSecurity(url: String) {
        val lowercaseUrl = url.lowercase(Locale.getDefault())
        // Detect sensitive pages: Live Mock Tests, Exams, Result Previews
        val isSensitive = lowercaseUrl.contains("live-mock-test") ||
                lowercaseUrl.contains("exam") ||
                lowercaseUrl.contains("test-screen") ||
                lowercaseUrl.contains("result-preview") ||
                lowercaseUrl.contains("mock-test") ||
                lowercaseUrl.contains("mcq") ||
                lowercaseUrl.contains("practice-test")
        
        _isSecurePage.value = isSensitive
    }

    // --- Bookmarks Actions ---
    fun toggleBookmark() {
        val url = _currentUrl.value
        val title = _pageTitle.value
        if (url.isEmpty() || url.contains("about:blank")) return

        viewModelScope.launch {
            if (_isBookmarked.value) {
                repository.deleteBookmarkByUrl(url)
                _isBookmarked.value = false
            } else {
                repository.insertBookmark(Bookmark(url, title))
                _isBookmarked.value = true
            }
        }
    }

    // --- History Actions ---
    private fun saveToHistory(url: String, title: String) {
        viewModelScope.launch {
            repository.insertHistory(HistoryItem(url = url, title = title))
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    // --- Notes Actions ---
    fun addOfflineNote(title: String, content: String) {
        viewModelScope.launch {
            repository.insertNote(OfflineNote(title = title, content = content, pageUrl = _currentUrl.value))
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch {
            repository.deleteNoteById(id)
        }
    }

    // --- Downloads Manager ---
    fun registerDownloadedMaterial(url: String, title: String, localFile: File, mimeType: String) {
        viewModelScope.launch {
            val fileId = UUID.randomUUID().toString()
            val download = DownloadedMaterial(
                id = fileId,
                url = url,
                title = title,
                localPath = localFile.absolutePath,
                sizeBytes = localFile.length(),
                mimeType = mimeType
            )
            repository.insertDownload(download)
        }
    }

    fun removeDownloadedMaterial(id: String, filePath: String) {
        viewModelScope.launch {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            repository.deleteDownloadById(id)
        }
    }

    // --- Streak & Usage Tracker ---
    private fun logTodayStudy() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(Date())
        viewModelScope.launch {
            // Log 60 seconds (or increment duration on log entry)
            repository.insertStreakLog(StudyStreak(date = todayStr, durationSeconds = 300))
        }
    }

    fun recordStudyTime(seconds: Long) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(Date())
        viewModelScope.launch {
            repository.insertStreakLog(StudyStreak(date = todayStr, durationSeconds = seconds))
        }
    }

    fun calculateCurrentStreak(streakLogs: List<StudyStreak>): Int {
        if (streakLogs.isEmpty()) return 0
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)

        val logDates = streakLogs.mapNotNull {
            try {
                dateFormat.parse(it.date)
            } catch (e: Exception) {
                null
            }
        }.sortedDescending()

        if (logDates.isEmpty()) return 0

        var currentStreak = 0
        val checkDate = Calendar.getInstance()
        checkDate.time = today.time

        // Check if there's a log for today or yesterday to continue streak
        val firstLogCalendar = Calendar.getInstance()
        firstLogCalendar.time = logDates[0]
        
        val diffDaysToday = ((today.timeInMillis - firstLogCalendar.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        if (diffDaysToday > 1) {
            return 0 // Streak broken
        }

        var i = 0
        while (i < logDates.size) {
            val logCal = Calendar.getInstance()
            logCal.time = logDates[i]
            
            val diff = ((checkDate.timeInMillis - logCal.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
            if (diff == 0) {
                currentStreak++
                checkDate.add(Calendar.DAY_OF_YEAR, -1)
            } else if (diff == 1) {
                // Skips to next check day
            } else if (diff > 1) {
                break // Streak broken
            }
            i++
        }
        return currentStreak
    }
}
