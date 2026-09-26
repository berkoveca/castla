package com.castla.mirror

import android.Manifest
import android.app.Activity
import com.castla.mirror.BuildConfig
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.castla.mirror.network.NetworkMonitor
import com.castla.mirror.service.MirrorForegroundService
import com.castla.mirror.service.TeslaBleScanner
import com.castla.mirror.service.TeslaDetectNotifier
import com.castla.mirror.shizuku.ShizukuSetup
import com.castla.mirror.ui.SettingsScreen
import com.castla.mirror.ui.MirroringMode
import com.castla.mirror.ui.StreamSettings
import com.castla.mirror.ui.MeshGradientBackground
import com.castla.mirror.ui.glassCard
import com.castla.mirror.update.ForceUpdateDialog
import com.castla.mirror.update.UpdateManager
import com.castla.mirror.update.UpdateManagerFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MIRROR_START_TIMEOUT_MS = 15_000L
        private const val PROJECTION_RESULT_TIMEOUT_MS = 8_000L
        private const val PROJECTION_FOCUS_RETURN_TIMEOUT_MS = 1_500L
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_RELEASES_API = "https://api.github.com/repos/RikkaApps/Shizuku/releases/latest"
        private const val SHIZUKU_APK_FILENAME = "shizuku.apk"
        private const val USB_CONFIG_PREFS = "usb_config_advisory"
        private const val KEY_SUPPRESS_USB_CONFIG_WARNING = "suppress_warning"
    }

    private var isStreaming by mutableStateOf(false)
    private var isPreparing by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var streamSettings by mutableStateOf(StreamSettings())
    private var shizukuInstalled by mutableStateOf(false)
    private var shizukuRunning by mutableStateOf(false)
    private var shizukuPermitted by mutableStateOf(false)
    private var isShizukuOnPowerAllowlist by mutableStateOf(false)
    private var isShizukuServiceConnected by mutableStateOf(false)
    private var showShizukuPermissionDialog by mutableStateOf(false)
    private var showUsbConfigWarningDialog by mutableStateOf(false)
    private var teslaAutoDetectEnabled by mutableStateOf(false)
    private var isPanelOff by mutableStateOf(false)
    private var cloudflareTunnelUrl by mutableStateOf<String?>(null)
    private var cloudflareTunnelActive by mutableStateOf(false)
    private var cloudflareTunnelError by mutableStateOf<String?>(null)
    private var tunnelAuthEnabled by mutableStateOf(false)
    private var teslaBleScanner: TeslaBleScanner? = null

    // Shizuku download state
    private var shizukuDownloadId: Long = -1L
    private var shizukuDownloadProgress by mutableFloatStateOf(-1f) // -1 = not downloading
    private var downloadProgressJob: kotlinx.coroutines.Job? = null

    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var shizukuSetup: ShizukuSetup
    private lateinit var updateManager: UpdateManager
    private var mirrorService: MirrorForegroundService? = null
    private var serviceBound = false
    private var bindRequested = false
    private var awaitingProjectionResult = false
    private var projectionResultTimeoutJob: kotlinx.coroutines.Job? = null
    private var projectionFocusRecoveryJob: kotlinx.coroutines.Job? = null
    private var isCleanupInProgress by mutableStateOf(false)
    private var pendingStartAfterCleanup = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MirrorForegroundService.LocalBinder
            mirrorService = localBinder.service
            if (!isStreaming) {
                isStreaming = localBinder.service.isRunning
            }
            val serviceWasAlreadyRunning = localBinder.service.isRunning
            if (streamSettings.mirroringMode == MirroringMode.FULL_SCREEN && !serviceWasAlreadyRunning) {
                localBinder.service.setBrowserConnectionListener { connected ->
                    if (connected) runOnUiThread { moveTaskToBack(true) }
                }
            }

            serviceBound = true
            bindRequested = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mirrorService = null
            serviceBound = false
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        awaitingProjectionResult = false
        projectionResultTimeoutJob?.cancel()
        projectionResultTimeoutJob = null
        projectionFocusRecoveryJob?.cancel()
        projectionFocusRecoveryJob = null
        Log.i(TAG, "MediaProjection result: code=${result.resultCode}, data=${result.data != null}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Toast.makeText(this, getString(R.string.toast_screen_capture_granted), Toast.LENGTH_SHORT).show()
            startMirrorService(result.resultCode, result.data!!)
        } else {
            isPreparing = false
            Toast.makeText(this, getString(R.string.toast_screen_capture_denied, result.resultCode), Toast.LENGTH_LONG).show()
            Log.w(TAG, "Screen capture denied or failed")
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestScreenCapture()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        proceedAfterNotificationPermission()
    }

    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "Startup permissions: $results")
    }

    /**
     * If the previous run crashed, offer to copy the full diagnostic report (it
     * contains the stack trace) right here — the Logs buttons live in Settings,
     * which may be the very screen that crashed.
     */
    private fun offerCrashReportIfAny() {
        val marker = java.io.File(filesDir, CastlaApp.CRASH_MARKER)
        if (!marker.exists()) return
        val summary = try { marker.readText().lineSequence().take(3).joinToString("\n") } catch (_: Throwable) { "" }
        marker.delete()
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("Castla crashed last time")
                .setMessage("$summary\n\nCopy the crash report to the clipboard to share it?")
                .setPositiveButton("Copy report") { _, _ ->
                    Thread {
                        val report = try {
                            com.castla.mirror.diagnostics.DiagnosticsCollector.buildReport(this)
                        } catch (t: Throwable) {
                            "report failed: $t\n$summary"
                        }
                        runOnUiThread {
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("castla-crash", report))
                            android.widget.Toast.makeText(this, "Crash report copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }
                .setNegativeButton("Dismiss", null)
                .show()
        } catch (_: Throwable) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        offerCrashReportIfAny()

        updateManager = UpdateManagerFactory.create()
        updateManager.checkForUpdate(this)

        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()
        streamSettings = StreamSettings.load(this)
        tunnelAuthEnabled = com.castla.mirror.network.TunnelSecurityConfig.run { passwordSet(load(this@MainActivity)) }

        shizukuInstalled = isShizukuInstalled()
        shizukuSetup = ShizukuSetup()
        if (shizukuInstalled) {
            shizukuSetup.init(this, bindService = false)
        }

        loadAutoDetectState()
        requestStartupPermissions()
        requestBatteryOptimizationExemption()
        refreshShizukuBatteryOptimizationState()

        // Handle intent extra to open settings (e.g. from screenshot automation)
        if (intent?.getBooleanExtra("open_settings", false) == true) {
            showSettings = true
        }

        // Sync UI streaming state when service stops externally
        // (e.g. notification action, thermal auto-stop, crash)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.serviceRunningFlow.collect { running ->
                    if (!running && isStreaming) {
                        isStreaming = false
                        if (!pendingStartAfterCleanup && !awaitingProjectionResult) {
                            isPreparing = false
                        }
                        mirrorService = null
                        if (serviceBound || bindRequested) {
                            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
                            serviceBound = false
                            bindRequested = false
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.cleanupInProgressFlow.collect { cleanupInProgress ->
                    val wasCleanupInProgress = isCleanupInProgress
                    isCleanupInProgress = cleanupInProgress
                    if (wasCleanupInProgress && !cleanupInProgress && pendingStartAfterCleanup) {
                        pendingStartAfterCleanup = false
                        Log.i(TAG, "Cleanup finished — continuing queued mirroring start")
                        beginMirroringStartFlow("cleanup_completed")
                    }
                }
            }
        }


        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.panelOffStateFlow.collect { state ->
                    isPanelOff = state != com.castla.mirror.policy.ScreenOffState.ACTIVE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.tunnelUrlFlow.collect { url ->
                    cloudflareTunnelUrl = url
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.tunnelActiveFlow.collect { active ->
                    cloudflareTunnelActive = active
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorForegroundService.tunnelErrorFlow.collect { err ->
                    cloudflareTunnelError = err
                }
            }
        }

        lifecycleScope.launch {
            shizukuSetup.state.collect { shizukuState ->
                Log.i(TAG, "Shizuku state: $shizukuState")
                val wasRunning = shizukuRunning
                shizukuRunning = shizukuState is com.castla.mirror.shizuku.ShizukuState.Running
                if (!wasRunning && shizukuRunning) {
                    refreshShizukuBatteryOptimizationState()
                }
                val wasPermitted = shizukuPermitted
                shizukuPermitted = shizukuState is com.castla.mirror.shizuku.ShizukuState.Running && shizukuState.permitted
                // Auto-continue mirroring after Shizuku permission granted
                if (!wasPermitted && shizukuPermitted && showShizukuPermissionDialog) {
                    showShizukuPermissionDialog = false
                    onStartMirroring()
                }
            }
        }

        lifecycleScope.launch {
            shizukuSetup.serviceConnected.collect { connected ->
                Log.i(TAG, "Shizuku PrivilegedService connected: $connected")
                isShizukuServiceConnected = connected
                if (connected) {
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ok = shizukuSetup.ensureShizukuHardened()
                        Log.i(TAG, "ensureShizukuHardened (MainActivity): $ok")
                        evaluateUsbConfigAdvisory()
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                updateManager.ForceUpdateOverlay(this@MainActivity)

                val thermalStatus by (mirrorService?.thermalStatus
                    ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()

                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        settings = streamSettings,
                        isStreaming = isStreaming,
                        thermalStatus = thermalStatus,
                        onSettingsChanged = { newSettings ->
                            streamSettings = newSettings
                            StreamSettings.save(this@MainActivity, newSettings)
                        },
                        onBackClick = {
                            tunnelAuthEnabled =
                                com.castla.mirror.network.TunnelSecurityConfig.run { passwordSet(load(this@MainActivity)) }
                            showSettings = false
                        }
                    )
                } else {
                    CastlaScreen(
                        isStreaming = isStreaming,
                        isPreparing = isPreparing,
                        shizukuInstalled = shizukuInstalled,
                        shizukuRunning = shizukuRunning,
                        shizukuPermitted = shizukuPermitted,
                        onStartClick = { onStartMirroring() },
                        onStopClick = { stopMirrorService() },
                        onSettingsClick = { showSettings = true },
                        onInstallShizuku = { downloadAndInstallShizuku() },
                        onOpenShizuku = { openShizukuApp() },
                        onGrantShizukuPermission = { shizukuSetup.requestPermission() },
                        shizukuDownloadProgress = shizukuDownloadProgress,
                        isPanelOff = isPanelOff,
                        onTogglePanelOff = { togglePanelOff() },
                        cloudflareTunnelUrl = cloudflareTunnelUrl,
                        cloudflareTunnelActive = cloudflareTunnelActive,
                        cloudflareTunnelError = cloudflareTunnelError,
                        tunnelAuthEnabled = tunnelAuthEnabled,
                        currentVersion = updateManager.currentVersion,
                        latestVersion = updateManager.latestVersion,
                        updateAvailable = updateManager.updateAvailable,
                        onUpdateClick = { updateManager.startUpdate(this@MainActivity) },
                        isShizukuOnPowerAllowlist = isShizukuOnPowerAllowlist,
                        onOpenShizukuBatterySettings = {
                            try {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to open battery optimization settings", e)
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.toast_battery_settings_fallback),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }

                if (showUsbConfigWarningDialog) {
                    UsbConfigWarningDialog(
                        onOpenDevOptions = {
                            showUsbConfigWarningDialog = false
                            openDeveloperOptions()
                        },
                        onDismiss = { showUsbConfigWarningDialog = false },
                        onDontShowAgain = {
                            suppressUsbConfigWarning()
                            showUsbConfigWarningDialog = false
                        }
                    )
                }
            }
        }

        if (intent?.getBooleanExtra("start_mirroring", false) == true && !isStreaming) {
            Log.i(TAG, "Start mirroring triggered from widget (cold launch)")
            onStartMirroring()
        }
        
        handleNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNewIntent(intent)
    }
    
    private fun handleNewIntent(intent: Intent?) {
        if (intent == null) return
        
        if (intent.getBooleanExtra("start_mirroring", false) && !isStreaming) {
            Log.i(TAG, "Start mirroring triggered from widget")
            onStartMirroring()
        }
        if (intent.getBooleanExtra("open_settings", false)) {
            showSettings = true
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateManager.onResume(this)
        refreshShizukuBatteryOptimizationState()
    }

    private fun refreshShizukuBatteryOptimizationState() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isShizukuOnPowerAllowlist = try {
            pm.isIgnoringBatteryOptimizations(SHIZUKU_PACKAGE)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Called on the IO thread once the Shizuku privileged service is connected.
     * Reads the persistent USB configuration via privileged getprop and, on
     * Samsung devices where MTP/PTP is the default, surfaces the advisory
     * dialog so the user can swap to "Charging only" in Developer Options.
     * No-op if the user previously dismissed via "Don't show again".
     */
    private fun evaluateUsbConfigAdvisory() {
        if (isUsbConfigWarningSuppressed()) return
        val advisory = try {
            shizukuSetup.classifyUsbConfig(Build.MANUFACTURER ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "classifyUsbConfig threw", e)
            return
        }
        Log.i(TAG, "USB config advisory: $advisory")
        if (advisory == com.castla.mirror.shizuku.UsbConfigChecker.Advisory.RiskyUsbConfig) {
            runOnUiThread { showUsbConfigWarningDialog = true }
        }
    }

    private fun isUsbConfigWarningSuppressed(): Boolean =
        getSharedPreferences(USB_CONFIG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUPPRESS_USB_CONFIG_WARNING, false)

    private fun suppressUsbConfigWarning() {
        getSharedPreferences(USB_CONFIG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SUPPRESS_USB_CONFIG_WARNING, true)
            .apply()
    }

    private fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "openDeveloperOptions: no matching Settings activity", e)
                Toast.makeText(
                    this,
                    getString(R.string.toast_dev_options_unavailable),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!awaitingProjectionResult) {
            projectionFocusRecoveryJob?.cancel()
            projectionFocusRecoveryJob = null
            return
        }
        if (hasFocus) {
            projectionFocusRecoveryJob?.cancel()
            projectionFocusRecoveryJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(PROJECTION_FOCUS_RETURN_TIMEOUT_MS)
                if (awaitingProjectionResult && hasWindowFocus()) {
                    Log.w(TAG, "MediaProjection result missing after focus returned")
                    awaitingProjectionResult = false
                    projectionResultTimeoutJob?.cancel()
                    projectionResultTimeoutJob = null
                    clearPreparingState(
                        getString(R.string.toast_error, "screen capture result missing")
                    )
                }
            }
        } else {
            projectionFocusRecoveryJob?.cancel()
            projectionFocusRecoveryJob = null
        }
    }

    override fun onStart() {
        super.onStart()
        val wasInstalled = shizukuInstalled
        shizukuInstalled = isShizukuInstalled()
        if (shizukuInstalled && !wasInstalled) {
            shizukuSetup.init(this, bindService = false)
        }
        loadAutoDetectState()

        if (!serviceBound && !bindRequested) {
            val intent = Intent(this, MirrorForegroundService::class.java)
            bindRequested = bindService(intent, serviceConnection, 0)
        }

        // Recover Shizuku if it died while we were backgrounded (typically from a
        // USB-unplug adbd respawn that wiped shell-UID processes). startActivity
        // from here is allowed because we're in the foreground — the same call
        // from binderDeadListener gets hit by BAL_BLOCK while the screen is
        // sleeping, which is why we also rely on this onStart hook.
        if (shizukuInstalled) {
            shizukuSetup.launchShizukuManagerIfLostSinceBoot()
        }
    }

    override fun onStop() {
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        projectionResultTimeoutJob?.cancel()
        projectionFocusRecoveryJob?.cancel()
        updateManager.destroy()
        networkMonitor.stopMonitoring()
        stopAutoDetect()
        shizukuSetup.release()
        downloadProgressJob?.cancel()
        try { unregisterReceiver(shizukuDownloadReceiver) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }

    // The connection link is provided exclusively by the Cloudflare tunnel
    // (see CloudflareTunnelManager), so there is no local-IP URL to compute or
    // display here.

    private fun togglePanelOff() {
        val service = mirrorService ?: MirrorForegroundService.instance ?: return
        if (isPanelOff) {
            service.restorePhysicalPanel()
        } else {
            val success = service.turnPanelOffForMirroring()
            if (!success) {
                android.widget.Toast.makeText(
                    this, "Screen off not available", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun downloadAndInstallShizuku() {
        if (shizukuDownloadProgress >= 0f) {
            Toast.makeText(this, "Download already in progress…", Toast.LENGTH_SHORT).show()
            return
        }

        shizukuDownloadProgress = 0f
        Toast.makeText(this, "Fetching latest Shizuku release…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Fetch latest release APK URL from GitHub API
                val url = java.net.URL(SHIZUKU_RELEASES_API)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                // Parse APK download URL from assets
                val apkUrl = org.json.JSONObject(json)
                    .getJSONArray("assets")
                    .let { assets ->
                        var downloadUrl: String? = null
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name")
                            if (name.endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                        downloadUrl
                    } ?: throw Exception("No APK found in latest release")

                Log.i(TAG, "Shizuku APK URL: $apkUrl")

                runOnUiThread { startShizukuDownload(apkUrl) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Shizuku release info", e)
                runOnUiThread {
                    shizukuDownloadProgress = -1f
                    Toast.makeText(this@MainActivity, "Failed to fetch release info: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startShizukuDownload(apkUrl: String) {
        // Delete any previous APK
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SHIZUKU_APK_FILENAME)
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Shizuku")
            .setDescription("Downloading Shizuku APK…")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, SHIZUKU_APK_FILENAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        shizukuDownloadId = dm.enqueue(request)
        Log.i(TAG, "Shizuku download started: id=$shizukuDownloadId")
        Toast.makeText(this, "Downloading Shizuku…", Toast.LENGTH_SHORT).show()

        // Register completion receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        registerReceiver(shizukuDownloadReceiver, filter, Context.RECEIVER_EXPORTED)

        startDownloadProgressPolling()
    }

    private val shizukuDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != shizukuDownloadId) return

            downloadProgressJob?.cancel()
            shizukuDownloadProgress = -1f

            try {
                unregisterReceiver(this)
            } catch (_: IllegalArgumentException) {}

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = dm.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    Log.i(TAG, "Shizuku APK download complete")
                    installShizukuApk()
                } else {
                    Log.e(TAG, "Shizuku download failed with status: $status")
                    Toast.makeText(context, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                }
                cursor.close()
            }
        }
    }

    private fun startDownloadProgressPolling() {
        downloadProgressJob?.cancel()
        downloadProgressJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (shizukuDownloadProgress >= 0f) {
                val query = DownloadManager.Query().setFilterById(shizukuDownloadId)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    if (bytesTotal > 0) {
                        shizukuDownloadProgress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                    }
                    cursor.close()
                }
                kotlinx.coroutines.delay(300)
            }
        }
    }

    private fun installShizukuApk() {
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SHIZUKU_APK_FILENAME)
        if (!apkFile.exists()) {
            Log.e(TAG, "Shizuku APK file not found: ${apkFile.absolutePath}")
            Toast.makeText(this, "APK file not found.", Toast.LENGTH_LONG).show()
            return
        }

        val apkUri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) {
            startActivity(intent)
        }
    }

    // ── Tesla Auto-Detect (BLE) ──────────────────────

    private fun loadAutoDetectState() {
        teslaAutoDetectEnabled = getSharedPreferences("castla_settings", MODE_PRIVATE)
            .getBoolean("auto_detect_enabled", false)
        if (teslaAutoDetectEnabled) {
            startAutoDetect()
        }
    }

    private fun toggleAutoDetect() {
        teslaAutoDetectEnabled = !teslaAutoDetectEnabled
        getSharedPreferences("castla_settings", MODE_PRIVATE).edit()
            .putBoolean("auto_detect_enabled", teslaAutoDetectEnabled).apply()
        if (teslaAutoDetectEnabled) {
            startAutoDetect()
            Toast.makeText(this, getString(R.string.toast_tesla_paired_auto_detect), Toast.LENGTH_SHORT).show()
        } else {
            stopAutoDetect()
            Toast.makeText(this, getString(R.string.toast_tesla_auto_detect_disabled), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAutoDetect() {
        // BLE scanner — detects Tesla BLE advertisement
        if (teslaBleScanner == null && hasBleScanPermissions()) {
            startBleScanner()
        }
    }

    private fun stopAutoDetect() {
        stopBleScanner()
    }

    private fun startBleScanner() {
        if (teslaBleScanner != null) return
        teslaBleScanner = TeslaBleScanner(this).also {
            it.start {
                TeslaDetectNotifier.showTeslaDetectedNotification(this)
            }
        }
        Log.i(TAG, "BLE scanner started")
    }

    private fun stopBleScanner() {
        teslaBleScanner?.stop()
        teslaBleScanner = null
    }

    private fun hasBleScanPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStartupPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (needed.isNotEmpty()) {
            Log.i(TAG, "Requesting startup permissions: $needed")
            startupPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Requesting battery optimization exemption")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request battery optimization exemption", e)
            }
        }
    }

    private fun clearPreparingState(message: String? = null, stopServiceIfNeeded: Boolean = false) {
        isPreparing = false
        if (stopServiceIfNeeded) {
            try { stopService(Intent(this, MirrorForegroundService::class.java)) } catch (_: Exception) {}
            mirrorService = null
            isStreaming = false
            if (serviceBound || bindRequested) {
                try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
                serviceBound = false
                bindRequested = false
            }
        }
        if (!message.isNullOrBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun queueStartAfterCleanup(reason: String) {
        pendingStartAfterCleanup = true
        isPreparing = true
        Log.i(TAG, "Queued mirroring start until cleanup completes: $reason")
    }

    private fun beginMirroringStartFlow(reason: String) {
        Log.i(TAG, "Beginning mirroring start flow: $reason")
        isPreparing = true
        Log.i(TAG, "isPreparing=true (starting permission flow)")

        // Check if Shizuku is running but permission not granted
        if (shizukuInstalled && shizukuRunning && !shizukuPermitted) {
            Log.i(TAG, "Shizuku running but not permitted, requesting permission")
            showShizukuPermissionDialog = true
            shizukuSetup.requestPermission()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        proceedAfterNotificationPermission()
    }

    private fun onStartMirroring() {
        Log.i(TAG, "onStartMirroring called")

        if (awaitingProjectionResult) {
            Log.i(TAG, "Ignoring duplicate start: awaiting MediaProjection result")
            return
        }

        if (isCleanupInProgress || MirrorForegroundService.isCleanupInProgress) {
            queueStartAfterCleanup("cleanup_in_progress")
            return
        }

        if (MirrorForegroundService.isServiceRunning || mirrorService?.isRunning == true) {
            queueStartAfterCleanup("service_still_running")
            stopMirrorService(preservePreparingState = true)
            return
        }

        beginMirroringStartFlow("user_request")
    }

    private fun proceedAfterNotificationPermission() {
        if (streamSettings.audioEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Requesting RECORD_AUDIO permission")
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            Log.i(TAG, "Launching screen capture consent dialog")
            awaitingProjectionResult = true
            projectionResultTimeoutJob?.cancel()
            projectionResultTimeoutJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(PROJECTION_RESULT_TIMEOUT_MS)
                if (awaitingProjectionResult && isPreparing) {
                    awaitingProjectionResult = false
                    projectionFocusRecoveryJob?.cancel()
                    projectionFocusRecoveryJob = null
                    Log.w(TAG, "MediaProjection result timed out after ${PROJECTION_RESULT_TIMEOUT_MS}ms")
                    clearPreparingState(
                        getString(R.string.toast_error, "screen capture request timed out")
                    )
                }
            }
            Toast.makeText(this, getString(R.string.toast_requesting_screen_capture), Toast.LENGTH_SHORT).show()
            mediaProjectionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            awaitingProjectionResult = false
            projectionResultTimeoutJob?.cancel()
            projectionResultTimeoutJob = null
            projectionFocusRecoveryJob?.cancel()
            projectionFocusRecoveryJob = null
            Log.e(TAG, "Failed to launch screen capture intent", e)
            clearPreparingState(getString(R.string.toast_error, e.message ?: "screen capture intent failed"))
        }
    }

    private fun startMirrorService(resultCode: Int, data: Intent) {
        launchMirrorService(resultCode, data)
    }

    private fun launchMirrorService(resultCode: Int, data: Intent) {
        // Refresh network state for diagnostics before the session starts.
        networkMonitor.refresh(forceLog = true)
        val intent = Intent(this, MirrorForegroundService::class.java).apply {
            putExtra(MirrorForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorForegroundService.EXTRA_DATA, data)
            putExtra(MirrorForegroundService.EXTRA_MAX_RESOLUTION,
                if (streamSettings.isAutoResolution) 0 else streamSettings.maxResolution.maxHeight)
            putExtra(MirrorForegroundService.EXTRA_FPS, streamSettings.fps) // FPS_AUTO is already 0
            putExtra(MirrorForegroundService.EXTRA_AUDIO, streamSettings.audioEnabled)
            putExtra(MirrorForegroundService.EXTRA_MIRRORING_MODE, streamSettings.mirroringMode.name)
            putExtra(MirrorForegroundService.EXTRA_TARGET_PACKAGE, streamSettings.targetAppPackage)
        }
        startForegroundService(intent)
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isStreaming = true
        Log.i(TAG, "isStreaming=true, isPreparing=$isPreparing (service started)")

        // 서비스 바인드 + 서버 실행 확인 후 최소 2초 뒤에 preparing 해제
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            while (mirrorService?.isRunning != true && isPreparing) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= MIRROR_START_TIMEOUT_MS) {
                    Log.w(TAG, "Mirror service start timed out after ${elapsed}ms")
                    clearPreparingState(
                        message = getString(R.string.toast_error, "mirroring start timed out"),
                        stopServiceIfNeeded = true
                    )
                    return@launch
                }
                kotlinx.coroutines.delay(100)
            }
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 2000L - elapsed
            if (remaining > 0) {
                kotlinx.coroutines.delay(remaining)
            }
            if (isPreparing) {
                isPreparing = false
                Log.i(TAG, "isPreparing=false (service ready, elapsed=${System.currentTimeMillis() - startTime}ms)")
            }
        }
    }

    private fun stopMirrorService(preservePreparingState: Boolean = false) {
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        stopService(Intent(this, MirrorForegroundService::class.java))
        mirrorService = null
        isStreaming = false
        if (!preservePreparingState) {
            isPreparing = false
        }
        com.castla.mirror.widget.MirrorWidgetProvider.updateAllWidgets(this)
    }
}

@Composable
fun CastlaScreen(
    isStreaming: Boolean,
    isPreparing: Boolean = false,
    shizukuInstalled: Boolean,
    shizukuRunning: Boolean,
    shizukuPermitted: Boolean = false,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onGrantShizukuPermission: () -> Unit = {},
    shizukuDownloadProgress: Float = -1f,
    isPanelOff: Boolean = false,
    onTogglePanelOff: () -> Unit = {},
    cloudflareTunnelUrl: String? = null,
    cloudflareTunnelActive: Boolean = false,
    cloudflareTunnelError: String? = null,
    tunnelAuthEnabled: Boolean = false,
    currentVersion: String = "",
    latestVersion: String? = null,
    updateAvailable: Boolean = false,
    onUpdateClick: () -> Unit = {},
    isShizukuOnPowerAllowlist: Boolean = true,
    onOpenShizukuBatterySettings: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    MeshGradientBackground {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = stringResource(id = R.string.app_name),
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Text(
                text = stringResource(id = R.string.subtitle_tesla_screen_mirroring),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            // Version info
            if (currentVersion.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "v$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    if (updateAvailable && latestVersion != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFF6B35).copy(alpha = 0.9f))
                                .clickable { onUpdateClick() }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v$latestVersion ${stringResource(id = R.string.version_update_available)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (latestVersion != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.version_latest),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF69F0AE).copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { onSettingsClick() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(id = R.string.btn_settings), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color(0xFFFFB300),
                        strokeWidth = 2.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isStreaming) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.5f))
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when {
                        isPreparing -> stringResource(id = R.string.status_preparing)
                        isStreaming -> stringResource(id = R.string.status_streaming_active)
                        else -> stringResource(id = R.string.status_ready_to_stream)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        isPreparing -> Color(0xFFFFB300)
                        isStreaming -> Color(0xFF69F0AE)
                        else -> Color.White.copy(alpha = 0.7f)
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedVisibility(visible = isStreaming) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(id = R.string.title_open_tesla_browser),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Local LAN IP addresses are intentionally NOT shown here.
                        // This app connects exclusively through a Cloudflare tunnel
                        // (quick or token-based named tunnel), so there is no
                        // hotspot / local-Wi-Fi connection path to advertise.
                        if (cloudflareTunnelUrl == null && cloudflareTunnelError == null) {
                            Text(
                                text = stringResource(id = R.string.home_waiting_for_tunnel),
                                fontSize = 15.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }

                        if (cloudflareTunnelActive || cloudflareTunnelError != null) {
                            Spacer(modifier = Modifier.height(20.dp))
                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(id = R.string.title_cloudflare_tunnel),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (cloudflareTunnelError != null) Color(0xFFFF8A80) else Color(0xFF7CB3FF),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (cloudflareTunnelUrl != null) {
                                Text(
                                    text = cloudflareTunnelUrl!!,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF7CB3FF),
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = stringResource(
                                        id = if (tunnelAuthEnabled) R.string.home_tunnel_password_protected
                                        else R.string.home_tunnel_password_missing
                                    ),
                                    fontSize = 13.sp,
                                    color = if (tunnelAuthEnabled) Color.White.copy(alpha = 0.6f) else Color(0xFFFF8A80),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            } else if (cloudflareTunnelError != null) {
                                Text(
                                    text = cloudflareTunnelError!!,
                                    fontSize = 14.sp,
                                    color = Color(0xFFFF8A80),
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Text(
                                    text = stringResource(id = R.string.status_tunnel_starting),
                                    fontSize = 14.sp,
                                    color = Color.White.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Screen off (panel-off) button — only visible when streaming
            AnimatedVisibility(visible = isStreaming) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onTogglePanelOff,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = if (isPanelOff) {
                            ButtonDefaults.buttonColors(containerColor = Color(0xFF69F0AE))
                        } else {
                            ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
                        },
                        border = if (!isPanelOff) BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)) else null
                    ) {
                        Text(
                            text = if (isPanelOff)
                                stringResource(id = R.string.btn_screen_on)
                            else
                                stringResource(id = R.string.btn_screen_off),
                            fontWeight = FontWeight.Bold,
                            color = if (isPanelOff) Color.Black else Color.White
                        )
                    }
                }
            }

            if (isStreaming) {
                Spacer(modifier = Modifier.height(24.dp))
            }

            AnimatedVisibility(visible = !shizukuInstalled || !shizukuRunning || !shizukuPermitted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF2D2000).copy(alpha = 0.8f))
                        .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(id = R.string.title_tesla_setup_required),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFFB300)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (!shizukuInstalled) {
                            Text(
                                text = stringResource(id = R.string.desc_shizuku_install_required),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFFD54F),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            if (shizukuDownloadProgress >= 0f) {
                                // Download in progress
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    LinearProgressIndicator(
                                        progress = { shizukuDownloadProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = Color(0xFFFFB300),
                                        trackColor = Color.White.copy(alpha = 0.2f),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Downloading… ${(shizukuDownloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFFD54F),
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Button(
                                    onClick = onInstallShizuku,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFB300)
                                    )
                                ) {
                                    Text(stringResource(id = R.string.btn_how_to_install_shizuku), color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else if (!shizukuRunning) {
                            Text(
                                text = stringResource(id = R.string.title_shizuku_setup_steps),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD54F)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(id = R.string.desc_shizuku_setup_steps),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD54F),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onOpenShizuku,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFB300)
                                    )
                                ) {
                                    Text(stringResource(id = R.string.btn_open_shizuku), color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://website-ten-sigma-42.vercel.app/setup"))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFFFFB300)
                                    ),
                                    border = BorderStroke(1.dp, Color(0xFFFFB300))
                                ) {
                                    Text(stringResource(id = R.string.btn_setup_guide), fontWeight = FontWeight.Bold)
                                }
                            }
                        } else if (!shizukuPermitted) {
                            Text(
                                text = stringResource(id = R.string.desc_shizuku_permission_required),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFFD54F),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onGrantShizukuPermission,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6D00)
                                )
                            ) {
                                Text(stringResource(id = R.string.btn_grant_shizuku_permission), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (!shizukuInstalled || !shizukuRunning || !shizukuPermitted) {
                Spacer(modifier = Modifier.height(24.dp))
            }

            AnimatedVisibility(visible = shizukuRunning && !isShizukuOnPowerAllowlist) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1A2D00).copy(alpha = 0.8f))
                            .border(1.dp, Color(0xFF8BC34A).copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                            .padding(20.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(id = R.string.desc_shizuku_battery_optimization),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCCFF90),
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onOpenShizukuBatterySettings,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF8BC34A)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF8BC34A))
                            ) {
                                Text(stringResource(id = R.string.btn_shizuku_battery_settings), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            Button(
                onClick = if (isStreaming) onStopClick else onStartClick,
                enabled = !isPreparing || isStreaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = when {
                    isPreparing && !isStreaming -> ButtonDefaults.buttonColors(
                        disabledContainerColor = Color.White.copy(alpha = 0.3f),
                        disabledContentColor = Color.Black.copy(alpha = 0.5f)
                    )
                    isStreaming -> ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    else -> ButtonDefaults.buttonColors(containerColor = Color.White)
                }
            ) {
                if (isPreparing && !isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.Black.copy(alpha = 0.5f),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = when {
                        isPreparing && !isStreaming -> stringResource(id = R.string.status_preparing)
                        isStreaming -> stringResource(id = R.string.btn_stop_mirroring)
                        else -> stringResource(id = R.string.btn_start_mirroring)
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isStreaming) Color.White else Color.Black
                )
            }

            if (!isStreaming) {
                Spacer(modifier = Modifier.height(32.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(20.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.title_how_to_use),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(id = R.string.desc_how_to_use),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            lineHeight = 24.sp
                        )
                    }
                }
            }


                Spacer(modifier = Modifier.height(48.dp))
            }

        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}

@Composable
private fun UsbConfigWarningDialog(
    onOpenDevOptions: () -> Unit,
    onDismiss: () -> Unit,
    onDontShowAgain: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1A1A2E))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(id = R.string.dialog_usb_config_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(id = R.string.dialog_usb_config_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Start
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onOpenDevOptions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(
                        text = stringResource(id = R.string.dialog_usb_config_open_dev_options),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = stringResource(id = R.string.dialog_usb_config_dismiss),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onDontShowAgain,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = stringResource(id = R.string.dialog_usb_config_dont_show),
                            color = Color.White.copy(alpha = 0.75f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
