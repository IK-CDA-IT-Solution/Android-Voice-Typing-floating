package com.nirzor.voicebubble.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.nirzor.voicebubble.updater.INSTALL_STATUS"
        private const val TAG = "UpdateInstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)

        // System-provided status messages may contain sensitive device/package details.
        Log.d(TAG, "PackageInstaller status received: $status, session=$sessionId")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent == null) {
                    UpdateManager.getInstance(context).onInstallFailure(
                        "ইনস্টলেশন নিশ্চিতকরণ খোলা যায়নি। আবার চেষ্টা করুন।",
                        sessionId
                    )
                    return
                }
                try {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not launch installer confirmation", e)
                    UpdateManager.getInstance(context).onInstallFailure(
                        "ইনস্টলেশন নিশ্চিতকরণ খোলা যায়নি। আবার চেষ্টা করুন।",
                        sessionId
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "App update installed successfully!")
                UpdateManager.getInstance(context).onInstallSuccess()
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateManager.getInstance(context).onInstallFailure(status, sessionId)
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                UpdateManager.getInstance(context).onInstallFailure(status, sessionId)
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                UpdateManager.getInstance(context).onInstallFailure(status, sessionId)
            }

            else -> {
                UpdateManager.getInstance(context).onInstallFailure(status, sessionId)
            }
        }
    }
}
