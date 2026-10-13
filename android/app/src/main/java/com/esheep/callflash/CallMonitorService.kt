package com.esheep.callflash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class CallMonitorService : Service() {

    private val session = System.identityHashCode(this)
    private lateinit var ble: BleController
    private lateinit var prefs: Prefs
    private var telephonyManager: TelephonyManager? = null
    private var modernCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ringing = false
    private var intentionalStop = false
    private val resendRunnable = object : Runnable {
        override fun run() {
            if (ringing) {
                ble.flashStart()
                handler.postDelayed(this, BleConstants.RESEND_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LampLink.owner = session
        prefs = Prefs(this)
        ble = BleController(this)
        ble.listener = object : BleController.Listener {
            override fun onStatus(text: String) {
                Log.d(TAG, text)
                updateNotification(text)
            }
            override fun onDeviceFound(device: android.bluetooth.BluetoothDevice, rssi: Int) {}
            override fun onConnected(device: android.bluetooth.BluetoothDevice) {
                publishLink(connected = true, connecting = false)
                updateNotification(getString(R.string.status_listening_connected))
            }
            override fun onDisconnected() {
                val mac = prefs.mac
                val retry = !intentionalStop && mac != null
                publishLink(connected = false, connecting = retry)
                if (retry) {
                    updateNotification(getString(R.string.status_reconnecting))
                    handler.postDelayed({
                        if (!intentionalStop) ble.connect(mac!!)
                    }, 3_000)
                }
            }
            override fun onWriteResult(ok: Boolean) {}
        }
        createChannel()
        startForeground(BleConstants.NOTIF_ID, buildNotification(getString(R.string.notification_text)))
        connectSaved()
        registerPhoneListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                intentionalStop = true
                prefs.monitorWanted = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 被系統拉起或 START_STICKY 重啟時再連一次
        if (prefs.hasSavedDevice()) {
            prefs.monitorWanted = true
            connectSaved()
        }
        return START_STICKY
    }

    /**
     * 從最近任務滑掉時，盡量自己再拉起前景服務（廠商仍可能清掉）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (intentionalStop || !prefs.hasSavedDevice() || !prefs.monitorWanted) return
        try {
            val restart = Intent(applicationContext, CallMonitorService::class.java)
            ContextCompat.startForegroundService(applicationContext, restart)
            Log.d(TAG, "restarted after task removed")
        } catch (e: Exception) {
            Log.w(TAG, "restart after swipe failed", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(resendRunnable)
        unregisterPhoneListener()
        if (ringing) {
            ble.flashStop()
        }
        ble.release()
        if (LampLink.owner == session) {
            LampLink.publish(this, connected = false, connecting = false)
            LampLink.owner = 0
        }
        // 刻意停止回 START_NOT_STICKY；其餘靠 START_STICKY / onTaskRemoved
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publishLink(connected: Boolean, connecting: Boolean) {
        if (LampLink.owner != session) return
        LampLink.publish(this, connected, connecting)
    }

    private fun connectSaved() {
        val mac = prefs.mac
        if (mac.isNullOrBlank()) {
            updateNotification(getString(R.string.no_device))
            return
        }
        if (ble.isConnected()) {
            publishLink(connected = true, connecting = false)
            updateNotification(getString(R.string.status_listening_connected))
            return
        }
        publishLink(connected = false, connecting = true)
        updateNotification(getString(R.string.status_connecting_lamp))
        ble.connect(mac)
    }

    private fun onCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (!ringing) {
                    ringing = true
                    ensureConnectedThen { ble.flashStart() }
                    handler.removeCallbacks(resendRunnable)
                    handler.postDelayed(resendRunnable, BleConstants.RESEND_INTERVAL_MS)
                }
            }
            TelephonyManager.CALL_STATE_IDLE,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (ringing || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    ringing = false
                    handler.removeCallbacks(resendRunnable)
                    ble.flashStop()
                }
            }
        }
    }

    private fun ensureConnectedThen(block: () -> Unit) {
        if (ble.isConnected()) {
            block()
        } else {
            val mac = prefs.mac ?: return
            ble.connect(mac)
            handler.postDelayed({
                if (ble.isConnected()) block() else Log.w(TAG, "BLE not connected for flash")
            }, 2_000)
        }
    }

    private fun registerPhoneListener() {
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onCallState(state)
                }
            }
            modernCallback = cb
            telephonyManager?.registerTelephonyCallback(mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    onCallState(state)
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterPhoneListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let {
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
        modernCallback = null
        legacyListener = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BleConstants.NOTIF_CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notification_text)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CallMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, BleConstants.NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_monitor), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(BleConstants.NOTIF_ID, buildNotification(content))
    }

    companion object {
        const val ACTION_STOP = "com.esheep.callflash.STOP"
        private const val TAG = "CallMonitorService"

        fun startIfWanted(context: android.content.Context) {
            val prefs = Prefs(context)
            if (!prefs.hasSavedDevice()) return
            prefs.monitorWanted = true
            val intent = Intent(context, CallMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
