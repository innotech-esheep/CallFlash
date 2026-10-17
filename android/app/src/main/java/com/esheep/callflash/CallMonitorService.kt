package com.esheep.callflash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
    /** 斷線後重連嘗試序號；連上後歸零。 */
    private var reconnectAttempt = 0
    private val resendRunnable = object : Runnable {
        override fun run() {
            if (ringing) {
                ble.flashStart()
                handler.postDelayed(this, BleConstants.RESEND_INTERVAL_MS)
            }
        }
    }
    private val reconnectRunnable = Runnable {
        if (!intentionalStop) connectSaved()
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
                resetReconnectBackoff()
                publishLink(connected = true, connecting = false)
                updateNotification(getString(R.string.status_listening_connected))
            }
            override fun onDisconnected() {
                val mac = prefs.mac
                val retry = !intentionalStop && mac != null
                publishLink(connected = false, connecting = retry)
                if (retry) {
                    updateNotification(getString(R.string.status_reconnecting))
                    scheduleReconnect()
                } else {
                    handler.removeCallbacks(reconnectRunnable)
                }
            }
            override fun onWriteResult(ok: Boolean) {}
        }
        createChannel()
        // Android 14+（targetSdk 34+）必須帶 FGS type；僅用 connectedDevice，
        // 不可用 phoneCall（需撥號器／MANAGE_OWN_CALLS，否則 SecurityException 閃退）
        try {
            ServiceCompat.startForeground(
                this,
                BleConstants.NOTIF_ID,
                buildNotification(getString(R.string.notification_text)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            // 仍嘗試以舊 API 撐住，避免服務立刻死掉
            try {
                @Suppress("DEPRECATION")
                startForeground(BleConstants.NOTIF_ID, buildNotification(getString(R.string.notification_text)))
            } catch (e2: Exception) {
                Log.e(TAG, "legacy startForeground also failed", e2)
                stopSelf()
                return
            }
        }
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
        handler.removeCallbacks(reconnectRunnable)
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
        if (!ble.hasConnectPermission()) {
            publishLink(connected = false, connecting = false)
            updateNotification(getString(R.string.status_ble_permission))
            Log.w(TAG, "skip connect: missing BLUETOOTH_CONNECT")
            return
        }
        if (ble.isConnected()) {
            resetReconnectBackoff()
            publishLink(connected = true, connecting = false)
            updateNotification(getString(R.string.status_listening_connected))
            return
        }
        // 主動連線前清掉排程，避免與 backoff 重疊雙連
        handler.removeCallbacks(reconnectRunnable)
        publishLink(connected = false, connecting = true)
        updateNotification(getString(R.string.status_connecting_lamp))
        try {
            ble.connect(mac)
        } catch (e: Exception) {
            Log.e(TAG, "connectSaved failed", e)
            val retry = !intentionalStop
            publishLink(connected = false, connecting = retry)
            updateNotification(
                if (retry) getString(R.string.status_reconnecting)
                else getString(R.string.status_connect_failed)
            )
            if (retry) scheduleReconnect()
        }
    }

    /**
     * 斷線自動重連：前約 1 分鐘較密（3–5s），其後 30s → 2 分 → 5 分，上限約 10 分。
     * 連上後由 [resetReconnectBackoff] 回到密集模式。全自動，無新 UI。
     */
    private fun nextReconnectDelayMs(): Long {
        val delay = when {
            reconnectAttempt < 3 -> 3_000L
            reconnectAttempt < 12 -> 5_000L // 約 3×3s + 9×5s ≈ 54s 密集期
            reconnectAttempt == 12 -> 30_000L
            reconnectAttempt == 13 -> 120_000L // 2 分
            reconnectAttempt == 14 -> 300_000L // 5 分
            else -> 600_000L // 上限約 10 分
        }
        reconnectAttempt++
        return delay
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        val delay = nextReconnectDelayMs()
        Log.d(TAG, "schedule reconnect in ${delay}ms (nextAttemptIndex=$reconnectAttempt)")
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun resetReconnectBackoff() {
        reconnectAttempt = 0
        handler.removeCallbacks(reconnectRunnable)
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
            if (!ble.hasConnectPermission()) {
                Log.w(TAG, "BLE not connected: no CONNECT permission")
                return
            }
            ble.connect(mac)
            handler.postDelayed({
                if (ble.isConnected()) block() else Log.w(TAG, "BLE not connected for flash")
            }, 2_000)
        }
    }

    private fun registerPhoneListener() {
        try {
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
        } catch (e: SecurityException) {
            Log.e(TAG, "registerPhoneListener SecurityException", e)
            updateNotification("缺少電話權限，無法聽來電")
        } catch (e: Exception) {
            Log.e(TAG, "registerPhoneListener failed", e)
        }
    }

    private fun unregisterPhoneListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let {
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {
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
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(BleConstants.NOTIF_ID, buildNotification(content))
        } catch (e: Exception) {
            Log.w(TAG, "updateNotification failed", e)
        }
    }

    companion object {
        const val ACTION_STOP = "com.esheep.callflash.STOP"
        private const val TAG = "CallMonitorService"

        fun startIfWanted(context: android.content.Context) {
            val prefs = Prefs(context)
            if (!prefs.hasSavedDevice()) return
            prefs.monitorWanted = true
            try {
                val intent = Intent(context, CallMonitorService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "startIfWanted failed", e)
            }
        }
    }
}
