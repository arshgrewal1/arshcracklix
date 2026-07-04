package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.*
import com.example.ui.AppViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.DecimalFormat
import java.util.*

class MainActivity : ComponentActivity() {
    
    private var webViewInstance: WebView? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        
        setContent {
            MyApplicationTheme {
                val viewModel: AppViewModel = viewModel()
                
                // Track dynamic secure flag (disable screenshots/screen recording on sensitive pages)
                val isSecurePage by viewModel.isSecurePage.collectAsStateWithLifecycle()
                LaunchedEffect(isSecurePage) {
                    if (isSecurePage) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                // Register network callback to automatically sync online/offline states
                val context = LocalContext.current
                DisposableEffect(Unit) {
                    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            viewModel.updateNetworkStatus()
                            // Force webview reload or refresh settings when network restores
                            webViewInstance?.post {
                                webViewInstance?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                            }
                        }

                        override fun onLost(network: Network) {
                            viewModel.updateNetworkStatus()
                            webViewInstance?.post {
                                webViewInstance?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                            }
                        }
                    }
                    
                    val networkRequest = NetworkRequest.Builder().build()
                    connectivityManager.registerNetworkCallback(networkRequest, callback)
                    networkCallback = callback
                    
                    onDispose {
                        connectivityManager.unregisterNetworkCallback(callback)
                    }
                }

                // Track Study Streak Session (accumulates local study duration while app is open)
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(10000) // update study metrics every 10 seconds
                        viewModel.recordStudyTime(10)
                    }
                }

                CracklixMainScreen(
                    viewModel = viewModel,
                    onRegisterWebView = { webViewInstance = it }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webViewInstance = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CracklixMainScreen(
    viewModel: AppViewModel,
    onRegisterWebView: (WebView) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
    val pageTitle by viewModel.pageTitle.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val progress by viewModel.loadingProgress.collectAsStateWithLifecycle()
    val isNetworkAvailable by viewModel.isNetworkAvailable.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isBookmarked.collectAsStateWithLifecycle()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var hasPageError by remember { mutableStateOf(false) }
    var showOfflineHubSheet by remember { mutableStateOf(false) }

    // Intercept standard Android back-button pressed event to navigate WebView back
    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    // Color Theme Definitions - Elegant Midnight Slate Vibe
    val spaceBlack = Color(0xFF0F111A)
    val cardBackground = Color(0xFF161A26)
    val accentPurple = Color(0xFF8B5CF6)
    val accentBlue = Color(0xFF3B82F6)
    val greenStatus = Color(0xFF10B981)
    val orangeStatus = Color(0xFFF59E0B)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
        ) {
            // Main WebView Interface
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webViewRef = this
                        onRegisterWebView(this)
                        
                        // Set WebView background to white to align with the Next.js theme
                        setBackgroundColor(android.graphics.Color.WHITE)
                        
                        // Disable scrollbars for a clean, fully native app look
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        
                        // WebView settings optimal for Offline Cache & Service Workers (PWA Wrapper)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.loadWithOverviewMode = false
                        settings.useWideViewPort = false
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        
                        // Disable system Force Dark Mode to prevent rendering, color-inversion, and overlapping glitches
                        @Suppress("DEPRECATION")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            settings.forceDark = WebSettings.FORCE_DARK_OFF
                        }
                        
                        // Allow third-party cookies to prevent login/session/state syncing glitches in practice hub
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        
                        // Allow mixed content so all mock test assets and secure/unsecure imagery load perfectly
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        
                        // Set standard cache configurations
                        settings.cacheMode = if (isNetworkAvailable) {
                            WebSettings.LOAD_DEFAULT
                        } else {
                            WebSettings.LOAD_CACHE_ELSE_NETWORK
                        }

                        // Enable Service Worker and App Cache
                        settings.mediaPlaybackRequiresUserGesture = false
                        
                        // Intercept file downloads (PDF study guides / mock tests)
                        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                            downloadStudyFile(
                                context = context,
                                url = url,
                                contentDisposition = contentDisposition,
                                mimeType = mimeType,
                                viewModel = viewModel,
                                scope = scope
                            )
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { viewModel.onUrlChanged(it) }
                                hasPageError = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let { viewModel.onUrlChanged(it) }
                                
                                // Dynamic casing injection script
                                // Translates aggressive ALL-CAPS words into clean Title Case while keeping boards capitalized
                                 val uppercaseScript = """
                                    (function() {
                                        const boards = [
                                            "PSPCL", "PSSSB", "PPSC", "CTET", "PSTET", "BFUHS", "PSCB", 
                                            "PSTCL", "RRB", "SSC", "UPSC", "GK", "IBPS", "PYQ", "MCQ", 
                                            "PWA", "API", "PDF"
                                        ];
                                        
                                        function toSmartTitleCase(str) {
                                            if (!str || str.trim() === "") return str;
                                            
                                            const hasLowercase = /[a-z]/.test(str);
                                            const hasUppercase = /[A-Z]/.test(str);
                                            
                                            // If it's already mixed-case, just ensure target board abbreviations are capitalized properly
                                            if (hasLowercase && hasUppercase) {
                                                let modified = str;
                                                boards.forEach(board => {
                                                    const regex = new RegExp("\\b" + board + "\\b", "gi");
                                                    modified = modified.replace(regex, board.toUpperCase());
                                                });
                                                return modified;
                                            }
                                            
                                            // Otherwise format fully (from all-caps or all-lowercase)
                                            return str.split(/([\s\-_:().]+)/).map(part => {
                                                if (/^[\s\-_:().]+$/.test(part) || part.length === 0) return part;
                                                
                                                const clean = part.toUpperCase().replace(/[^A-Z0-9]/g, "");
                                                if (boards.includes(clean)) {
                                                    return clean;
                                                }
                                                
                                                return part.charAt(0).toUpperCase() + part.slice(1).toLowerCase();
                                            }).join("");
                                        }

                                        function updateUIForMobileApp() {
                                            const isLoginPage = window.location.pathname.includes('/login');
                                            const bottomNav = document.querySelector('nav.fixed.bottom-0');
                                            if (bottomNav) {
                                                if (isLoginPage) {
                                                    bottomNav.style.setProperty('display', 'none', 'important');
                                                } else {
                                                    bottomNav.style.removeProperty('display');
                                                }
                                            }
                                        }

                                        function formatTextElements() {
                                            // Targeted query selector matching headings and large mock titles
                                            const selectors = "h1, h2, h3, h4, .text-xl, .text-lg, .text-2xl, .font-black";
                                            const elements = document.querySelectorAll(selectors);
                                            
                                            elements.forEach(el => {
                                                if (el.dataset.casingProcessed) return;
                                                
                                                // Strictly skip structural/navigation/interactive containers to avoid layout breaks
                                                if (el.closest('nav') || el.closest('button') || el.closest('svg') || el.closest('a.button') || el.closest('[role=\"button\"]')) {
                                                    return;
                                                }
                                                
                                                let mutated = false;
                                                const nodes = el.childNodes;
                                                for (let i = 0; i < nodes.length; i++) {
                                                    const node = nodes[i];
                                                    if (node.nodeType === Node.TEXT_NODE) {
                                                        const original = node.nodeValue;
                                                        if (original && original.trim() !== "") {
                                                            const isAllCaps = original === original.toUpperCase() && /[A-Z]/.test(original);
                                                            if (isAllCaps || el.classList.contains("uppercase")) {
                                                                const processed = toSmartTitleCase(original);
                                                                if (original !== processed) {
                                                                    node.nodeValue = processed;
                                                                    mutated = true;
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                if (mutated || el.classList.contains("uppercase")) {
                                                    el.classList.remove("uppercase");
                                                    el.style.textTransform = "none";
                                                }
                                                
                                                el.dataset.casingProcessed = "true";
                                            });
                                        }

                                        let formatTimeout = null;
                                        function triggerFormat() {
                                            if (formatTimeout) return;
                                            formatTimeout = setTimeout(() => {
                                                formatTimeout = null;
                                                
                                                // Temporarily disconnect observer to prevent triggering mutation callbacks from our own modifications
                                                observer.disconnect();
                                                try {
                                                    formatTextElements();
                                                    updateUIForMobileApp();
                                                } catch (e) {
                                                    console.error("Format error", e);
                                                } finally {
                                                    // Re-observe once our updates are fully committed
                                                    observer.observe(document.body, { childList: true, subtree: true });
                                                }
                                            }, 150);
                                        }
                                        
                                        const observer = new MutationObserver(() => {
                                            triggerFormat();
                                        });
                                        
                                        // Avoid rendering heavy Tailwind box-shadows (like shadow-2xl, shadow-lg) as solid black artifacts/shapes on mobile WebViews,
                                        // and re-position the Radix toast container to bottom-20 on mobile viewports.
                                        const style = document.createElement("style");
                                        style.innerHTML = " .shadow-2xl, .shadow-xl, .shadow-lg, .shadow-md, .shadow { box-shadow: 0 2px 6px rgba(15, 23, 42, 0.06) !important; } .rounded-xl, .rounded-2xl, .rounded-3xl { transform: translate3d(0,0,0) !important; -webkit-transform: translate3d(0,0,0) !important; } @media (max-width: 640px) { ol[role='region'], .z-\\\\[100\\\\] { top: auto !important; bottom: 80px !important; left: 50% !important; transform: translateX(-50%) !important; width: 90% !important; max-width: 90% !important; flex-direction: column-reverse !important; } } ";
                                        document.head.appendChild(style);
                                        
                                        // Initial run
                                        try {
                                            formatTextElements();
                                            updateUIForMobileApp();
                                        } catch(e) {}
                                        
                                        observer.observe(document.body, { childList: true, subtree: true });
                                        
                                        // Periodically check page changes to ensure robust bottom nav hiding/showing during spa client-side routing
                                        setInterval(updateUIForMobileApp, 250);
                                    })();
                                """.trimIndent()
                                
                                view?.evaluateJavascript(uppercaseScript, null)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                // Only trigger error if the request is for the main page
                                if (request?.isForMainFrame == true) {
                                    hasPageError = true
                                }
                                super.onReceivedError(view, request, error)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val urlStr = request?.url?.toString() ?: ""
                                
                                // Intercept direct PDF / APK clicks inside navigation flow
                                if (urlStr.endsWith(".apk", ignoreCase = true) || urlStr.endsWith(".pdf", ignoreCase = true)) {
                                    downloadStudyFile(
                                        context = context,
                                        url = urlStr,
                                        contentDisposition = null,
                                        mimeType = if (urlStr.endsWith(".apk", ignoreCase = true)) "application/vnd.android.package-archive" else "application/pdf",
                                        viewModel = viewModel,
                                        scope = scope
                                    )
                                    return true
                                }
                                
                                // Force internal navigation inside WebView unless external schema
                                if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
                                    return false
                                }
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr))
                                    context.startActivity(intent)
                                    return true
                                } catch (e: Exception) {
                                    return false
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                viewModel.onProgressChanged(newProgress)
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                title?.let { viewModel.onTitleChanged(it) }
                            }

                            override fun onJsAlert(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: JsResult?
                            ): Boolean {
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("Cracklix")
                                    .setMessage(message)
                                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                                        result?.confirm()
                                        dialog.dismiss()
                                    }
                                    .setCancelable(false)
                                    .show()
                                return true
                            }

                            override fun onJsConfirm(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: JsResult?
                            ): Boolean {
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("Confirm")
                                    .setMessage(message)
                                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                                        result?.confirm()
                                        dialog.dismiss()
                                    }
                                    .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                                        result?.cancel()
                                        dialog.dismiss()
                                    }
                                    .setCancelable(false)
                                    .show()
                                return true
                            }
                        }

                        // Load initial Base URL
                        loadUrl(baseUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { webView ->
                    // Make sure web cache loads properly on network switches
                    webView.settings.cacheMode = if (isNetworkAvailable) {
                        WebSettings.LOAD_DEFAULT
                    } else {
                        WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                }
            )

            // Slim horizontal loading progress indicator at the top
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = accentPurple,
                    trackColor = Color.Transparent
                )
            }

            // Beautiful custom Offline Fallback overlay if page fails to load and internet is absent
            AnimatedVisibility(
                visible = hasPageError && !isNetworkAvailable,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(spaceBlack)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBackground),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(orangeStatus.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = "Disconnected Logo",
                                    tint = orangeStatus,
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .size(40.dp)
                                )
                            }
                            
                            Text(
                                text = "Study Portal Offline",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = "You are currently offline. Any previously viewed screens, lessons, mock tests, and downloaded PDFs are fully available in the local companion folder.",
                                fontSize = 14.sp,
                                color = Color.LightGray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Button(
                                onClick = {
                                    hasPageError = false
                                    webViewRef?.reload()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentPurple),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry Connecting", fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = { showOfflineHubSheet = true },
                                border = BoxBorder(Color.DarkGray),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open Offline Assistant", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet: Dynamic Offline Assistant Hub
    if (showOfflineHubSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOfflineHubSheet = false },
            containerColor = cardBackground,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.DarkGray) }
        ) {
            OfflineHubContent(
                viewModel = viewModel,
                onNavigateUrl = { url ->
                    showOfflineHubSheet = false
                    hasPageError = false
                    webViewRef?.loadUrl(url)
                }
            )
        }
    }
}

// Helper fun to define thin borders
@Composable
fun BoxBorder(color: Color) = remember(color) {
    androidx.compose.foundation.BorderStroke(1.dp, color)
}

// --- Dynamic Offline Assistant Hub Content (5 Tabs: Stats, Downloads, Notes, Links, Settings) ---
@Composable
fun OfflineHubContent(
    viewModel: AppViewModel,
    onNavigateUrl: (String) -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) }
    
    val tabs = listOf("Stats", "Downloads", "Notes", "History", "Settings")
    val tabIcons = listOf(
        Icons.AutoMirrored.Filled.TrendingUp,
        Icons.Default.Book,
        Icons.AutoMirrored.Filled.Notes,
        Icons.Default.Bookmark,
        Icons.Default.Settings
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hub Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Study Companion Hub",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            // Modern Pill Streak Counter
            val streaks by viewModel.streaks.collectAsStateWithLifecycle()
            val currentStreak = viewModel.calculateCurrentStreak(streaks)
            Surface(
                color = Color(0xFFFF5722).copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp),
                border = BoxBorder(Color(0xFFFF5722))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = "Streak",
                        tint = Color(0xFFFF5722),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "$currentStreak Day Streak",
                        fontSize = 12.sp,
                        color = Color(0xFFFF5722),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Horizontal Navigation Tabs
        ScrollableTabRow(
            selectedTabIndex = activeTab,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            edgePadding = 0.dp,
            indicator = { tabPositions ->
                if (activeTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                        color = Color(0xFF8B5CF6)
                    )
                }
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = activeTab == index,
                    onClick = { activeTab = index },
                    text = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(tabIcons[index], contentDescription = title, modifier = Modifier.size(18.dp)) },
                    selectedContentColor = Color(0xFF8B5CF6),
                    unselectedContentColor = Color.Gray
                )
            }
        }

        // Tab Content Window
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (activeTab) {
                0 -> StudyStatsTab(viewModel)
                1 -> DownloadsTab(viewModel)
                2 -> OfflineNotesTab(viewModel)
                3 -> HistoryBookmarksTab(viewModel, onNavigateUrl)
                4 -> SettingsTab(viewModel)
            }
        }
    }
}

// 1. STATS TAB COMPOSABLE
@Composable
fun StudyStatsTab(viewModel: AppViewModel) {
    val streaks by viewModel.streaks.collectAsStateWithLifecycle()
    val totalStudySeconds by viewModel.totalStudyTime.collectAsStateWithLifecycle()
    val totalHours = (totalStudySeconds ?: 0L) / 3600f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2438))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Total App Study Time", color = Color.LightGray, fontSize = 13.sp)
                    Text(
                        text = "${DecimalFormat("#.##").format(totalHours)} Hours",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8B5CF6)
                    )
                    Text("This metric syncs dynamically with the server when online.", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }

        item {
            Text("Study Consistency Log", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
        }

        if (streaks.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Start studying to record local logs!", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            items(streaks.take(7)) { streak ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.5f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                        Text(text = streak.date, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        text = "${streak.durationSeconds / 60} mins",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// 2. DOWNLOADS TAB COMPOSABLE
@Composable
fun DownloadsTab(viewModel: AppViewModel) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (downloads.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                Text("No downloaded study materials found.", color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
                Text("When a PDF is opened, click 'Download' to cache it offline.", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(downloads) { doc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B))
                        .clickable {
                            openLocalPdf(context, doc.localPath)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "PDF File", tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                        Column {
                            Text(
                                text = doc.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sizeKb = doc.sizeBytes / 1024f
                            Text(
                                text = "${DecimalFormat("#.#").format(sizeKb)} KB",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.removeDownloadedMaterial(doc.id, doc.localPath) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete File", tint = Color.Gray)
                    }
                }
            }
        }
    }
}

// 3. OFFLINE NOTES TAB COMPOSABLE
@Composable
fun OfflineNotesTab(viewModel: AppViewModel) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var noteTitle by remember { mutableStateOf("") }
    var noteContent by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Take Offline Study Note", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                OutlinedTextField(
                    value = noteTitle,
                    onValueChange = { noteTitle = it },
                    placeholder = { Text("Topic / Title", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF8B5CF6)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = noteContent,
                    onValueChange = { noteContent = it },
                    placeholder = { Text("Write your notes here...", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF8B5CF6)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                Button(
                    onClick = {
                        if (noteTitle.trim().isNotEmpty() && noteContent.trim().isNotEmpty()) {
                            viewModel.addOfflineNote(noteTitle, noteContent)
                            noteTitle = ""
                            noteContent = ""
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save Note")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notes) { note ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151926)),
                    border = BoxBorder(Color.DarkGray)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(note.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            IconButton(
                                onClick = { viewModel.deleteNote(note.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Note", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(note.content, color = Color.LightGray, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// 4. BOOKMARKS & HISTORY TAB COMPOSABLE
@Composable
fun HistoryBookmarksTab(
    viewModel: AppViewModel,
    onNavigateUrl: (String) -> Unit
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    var currentSubTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabRow(
            selectedTabIndex = currentSubTab,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            indicator = {}
        ) {
            Tab(
                selected = currentSubTab == 0,
                onClick = { currentSubTab = 0 },
                text = { Text("Bookmarks (${bookmarks.size})", fontSize = 13.sp) },
                selectedContentColor = Color(0xFF8B5CF6),
                unselectedContentColor = Color.Gray
            )
            Tab(
                selected = currentSubTab == 1,
                onClick = { currentSubTab = 1 },
                text = { Text("History logs", fontSize = 13.sp) },
                selectedContentColor = Color(0xFF8B5CF6),
                unselectedContentColor = Color.Gray
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (currentSubTab == 0) {
                if (bookmarks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No bookmarked lessons found.", color = Color.Gray, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bookmarks) { bookmark ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateUrl(bookmark.url) },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(18.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(bookmark.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(bookmark.url, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (history.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Navigation logs are empty.", color = Color.Gray, fontSize = 14.sp)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.clearAllHistory() },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Clear logs", fontSize = 12.sp)
                        }
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(history) { log ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateUrl(log.url) },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.History, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                                        Column {
                                            Text(log.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(log.url, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 5. SETTINGS COMPOSABLE
@Composable
fun SettingsTab(viewModel: AppViewModel) {
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    var urlInput by remember { mutableStateOf(baseUrl) }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Portal Server Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "You can customize the base server URL to load a local development server, vercel deployment, or test domain.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Base Server URL") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF8B5CF6)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                Button(
                    onClick = {
                        if (urlInput.trim().isNotEmpty()) {
                            viewModel.setBaseUrl(urlInput)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Apply Base URL")
                }
            }
        }
        
        Text(
            text = "Engine Version Code: 1.0.0 (Offline Enabled Core)",
            color = Color.Gray,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// --- UTILITY METHODS ---

// Downloads files natively via network call in Coroutines background thread
private fun downloadStudyFile(
    context: Context,
    url: String,
    contentDisposition: String?,
    mimeType: String?,
    viewModel: AppViewModel,
    scope: CoroutineScope
) {
    scope.launch(Dispatchers.IO) {
        try {
            var actualMimeType = mimeType
            var isApk = false
            if (url.endsWith(".apk", ignoreCase = true) || mimeType == "application/vnd.android.package-archive") {
                isApk = true
                actualMimeType = "application/vnd.android.package-archive"
            }

            var fileName = URLUtil.guessFileName(url, contentDisposition, actualMimeType)
            if (fileName.isNullOrEmpty() || fileName.contains("bin")) {
                fileName = if (isApk) {
                    "cracklix_" + System.currentTimeMillis() + ".apk"
                } else {
                    "material_" + System.currentTimeMillis() + ".pdf"
                }
            } else if (fileName.endsWith(".apk", ignoreCase = true)) {
                isApk = true
                actualMimeType = "application/vnd.android.package-archive"
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Downloading ${if (isApk) "APK Update" else "Material"}...", Toast.LENGTH_SHORT).show()
            }

            val request = Request.Builder().url(url).build()
            val client = OkHttpClient()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body
                if (responseBody != null) {
                    val bytes = responseBody.bytes()
                    val downloadFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    val localFile = File(downloadFolder, fileName)
                    localFile.writeBytes(bytes)
                    
                    if (isApk) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "APK Downloaded! Starting installation...", Toast.LENGTH_LONG).show()
                            installApk(context, localFile)
                        }
                    } else {
                        viewModel.registerDownloadedMaterial(
                            url = url,
                            title = fileName,
                            localFile = localFile,
                            mimeType = actualMimeType ?: "application/pdf"
                        )
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Saved to Study Companion: $fileName", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Material download failed: Code ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error saving study guide: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// Launches installation prompt for a downloaded APK
private fun installApk(context: Context, file: File) {
    try {
        if (!file.exists()) {
            Toast.makeText(context, "APK file not found.", Toast.LENGTH_SHORT).show()
            return
        }
        val authority = "${context.packageName}.fileprovider"
        val fileUri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Failed to start installation: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

// Securely launches system PDF / content opener via FileProvider
@SuppressLint("QueryPermissionsNeeded")
private fun openLocalPdf(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist or has been deleted.", Toast.LENGTH_SHORT).show()
            return
        }

        val authority = "${context.packageName}.fileprovider"
        val fileUri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "Open Study Guide")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Could not open file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
