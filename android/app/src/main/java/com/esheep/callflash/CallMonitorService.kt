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
    private var destroyed = false
    /** 斷線後重連嘗試序號；連上後歸零。 */
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    /** 連上後要補送的閃燈（測試或響鈴）。響鈴中一律改送開始閃。 */
    private var pendingOnConnect: (() -> Unit)? = null
    /** true 表示 pending 只為這通響鈴；掛斷後清掉，避免通話結束才補閃。 */
    private var pendingIsRing = false
    private var flashWaitTries = 0
    private val resendRunnable = object : Runnable {
        override fun run() {
            if (!ringing || destroyed) return
            if (ble.isConnected()) {
                ble.flashStart()
            } else {
                ensureConnectedThen(forRing = true) { if (ringing) ble.flashStart() }
            }
            handler.postDelayed(this, BleConstants.RESEND_INTERVAL_MS)
        }
    }
    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        if (!intentionalStop && !destroyed && prefs.monitorWanted && prefs.hasSavedDevice()) {
            connectSaved()
        }
    }
    private val testStopRunnable = Runnable {
        if (!ringing && !destroyed) ble.flashStop()
    }
    private val flashWaitTimeout = object : Runnable {
        override fun run() {
            if (destroyed || ble.isConnected()) return
            if (pendingOnConnect == null && !ringing) return
            flashWaitTries++
            if (flashWaitTries > 5) {
                Log.w(TAG, "flash wait exceeded; reconnect loop keeps trying")
                return
            }
            Log.w(TAG, "lamp still down while flash is pending")
            if (!reconnectScheduled && !ble.isLinkBusy() && !intentionalStop && prefs.monitorWanted) {
                connectSaved()
            }
            handler.postDelayed(this, FLASH_WAIT_MS)
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
                resetReconnectBackoff()
                publishLink(connected = true, connecting = false)
                updateNotification(getString(R.string.status_listening_connected))
                handler.removeCallbacks(flashWaitTimeout)
                flashWaitTries = 0
                val pending = pendingOnConnect
                val ringPending = pendingIsRing
                pendingOnConnect = null
                pendingIsRing = false
                // 連上時若還在響，補送開始閃；測試閃燈只在沒在響時送。
                if (ringing) {
                    ble.flashStart()
                } else if (pending != null && !ringPending) {
                    pending()
                }
            }
            override fun onDisconnected() {
                if (destroyed) return
                val retry = !intentionalStop && prefs.monitorWanted && prefs.hasSavedDevice()
                publishLink(connected = false, connecting = retry)
                if (retry) {
                    updateNotification(getString(R.string.status_reconnecting))
                    scheduleReconnect()
                } else {
                    reconnectScheduled = false
                    handler.removeCallbacks(reconnectRunnable)
                }
            }
            override fun onWriteResult(ok: Boolean) {
                if (ok || destroyed || intentionalStop || !prefs.monitorWanted) return
                handler.post {
                    if (destroyed || intentionalStop || !prefs.monitorWanted) return@post
                    Log.w(TAG, "write failed while monitoring; reconnect")
                    if (ble.isConnected()) {
                        ble.disconnect()
                    } else if (!reconnectScheduled && !ble.isLinkBusy()) {
                        scheduleReconnect()
                    }
                }
            }
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
            ACTION_TEST -> {
                if (prefs.hasSavedDevice()) {
                    prefs.monitorWanted = true
                    beginTestFlash()
                }
                return START_STICKY
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
        destroyed = true
        handler.removeCallbacks(resendRunnable)
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(testStopRunnable)
        handler.removeCallbacks(flashWaitTimeout)
        pendingOnConnect = null
        pendingIsRing = false
        unregisterPhoneListener()
        if (ringing) {
            ringing = false
            ble.flashStop()
        }
        ble.listener = null
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
        reconnectScheduled = false
        publishLink(connected = false, connecting = true)
        updateNotification(getString(R.string.status_connecting_lamp))
        val accepted = try {
            ble.connect(mac)
        } catch (e: Exception) {
            Log.e(TAG, "connectSaved failed", e)
            false
        }
        if (!accepted) {
            val retry = !intentionalStop && !destroyed && prefs.monitorWanted
            publishLink(connected = false, connecting = retry)
            updateNotification(
                if (retry) getString(R.string.status_reconnecting)
                else getString(R.string.status_connect_failed)
            )
            if (retry) scheduleReconnect()
        }
    }

    /**
     * 斷線重連：前 3 次 3 秒、接著約 7 次 5 秒，之後固定 30 秒。
     * 不再拉到 10 分鐘，避免燈已回來、來電卻要等很久或重開 App。
     * 連上後由 [resetReconnectBackoff] 回到密集模式。
     */
    private fun nextReconnectDelayMs(): Long {
        val delay = when {
            reconnectAttempt < 3 -> 3_000L
            reconnectAttempt < 10 -> 5_000L
            else -> MAX_RECONNECT_DELAY_MS
        }
        reconnectAttempt++
        return delay
    }

    private fun scheduleReconnect() {
        if (destroyed || intentionalStop || !prefs.monitorWanted || !prefs.hasSavedDevice()) return
        handler.removeCallbacks(reconnectRunnable)
        val delay = nextReconnectDelayMs()
        reconnectScheduled = true
        Log.d(TAG, "schedule reconnect in ${delay}ms (attempt=$reconnectAttempt)")
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun resetReconnectBackoff() {
        reconnectAttempt = 0
        reconnectScheduled = false
        handler.removeCallbacks(reconnectRunnable)
    }

    private fun onCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (!ringing) {
                    ringing = true
                    handler.removeCallbacks(testStopRunnable)
                    ensureConnectedThen(forRing = true) { if (ringing) ble.flashStart() }
                    handler.removeCallbacks(resendRunnable)
                    handler.postDelayed(resendRunnable, BleConstants.RESEND_INTERVAL_MS)
                }
            }
            TelephonyManager.CALL_STATE_IDLE,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (ringing || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    ringing = false
                    handler.removeCallbacks(resendRunnable)
                    if (pendingIsRing) {
                        pendingOnConnect = null
                        pendingIsRing = false
                        handler.removeCallbacks(flashWaitTimeout)
                    }
                    if (ble.isConnected()) {
                        ble.flashStop()
                    } else if (
                        !reconnectScheduled && !ble.isLinkBusy() &&
                        prefs.monitorWanted && !intentionalStop && !destroyed
                    ) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    /**
     * 已連上就立刻做；否則先連，等 [BleController.Listener.onConnected] 再做。
     * 另加較長的等待，連線 callback 沒來時會再踢一次，而不是只等 2 秒就放棄。
     */
    private fun ensureConnectedThen(forRing: Boolean = false, block: () -> Unit) {
        if (destroyed) return
        if (ble.isConnected()) {
            block()
            return
        }
        pendingOnConnect = block
        pendingIsRing = forRing
        flashWaitTries = 0
        val mac = prefs.mac
        if (mac.isNullOrBlank() || !ble.hasConnectPermission()) {
            Log.w(TAG, "BLE not connected: missing device or CONNECT permission")
            if (!forRing) {
                pendingOnConnect = null
                pendingIsRing = false
            }
            return
        }
        connectSaved()
        handler.removeCallbacks(flashWaitTimeout)
        handler.postDelayed(flashWaitTimeout, FLASH_WAIT_MS)
    }

    /** 測試閃燈走服務的 GATT，不在 Activity 另開一條連線。 */
    private fun beginTestFlash() {
        if (destroyed || !prefs.hasSavedDevice()) return
        if (ringing) {
            ensureConnectedThen(forRing = true) { if (ringing) ble.flashStart() }
            return
        }
        ensureConnectedThen {
            ble.flashStart()
            if (!ringing) {
                handler.removeCallbacks(testStopRunnable)
                handler.postDelayed(testStopRunnable, BleConstants.TEST_FLASH_MS)
            }
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
        const val ACTION_TEST = "com.esheep.callflash.TEST_FLASH"
        private const val TAG = "CallMonitorService"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val FLASH_WAIT_MS = 20_000L

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

        /** 測試閃燈：只讓服務連已記住的燈，Activity 不得再 connectGatt。 */
        fun requestTestFlash(context: android.content.Context) {
            try {
                val intent = Intent(context, CallMonitorService::class.java).setAction(ACTION_TEST)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "requestTestFlash failed", e)
            }
        }
    }
}
