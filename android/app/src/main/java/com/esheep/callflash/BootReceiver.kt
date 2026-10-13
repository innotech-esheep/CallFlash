package com.esheep.callflash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 開機後若已記住設備，自動拉起前景服務連燈並聽來電。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val prefs = Prefs(context)
        if (!prefs.hasSavedDevice()) {
            Log.d(TAG, "skip boot start: no saved device")
            return
        }
        prefs.monitorWanted = true
        try {
            val svc = Intent(context, CallMonitorService::class.java)
            ContextCompat.startForegroundService(context, svc)
            Log.d(TAG, "started CallMonitorService after boot")
        } catch (e: Exception) {
            Log.w(TAG, "boot start failed", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
