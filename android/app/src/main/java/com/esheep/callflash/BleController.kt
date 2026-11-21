package com.esheep.callflash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

/**
 * 一條 GATT 客戶端。
 * 畫面掃描用 [scanOnly]；已記住的燈只能由 [CallMonitorService] 連線，
 * 否則會跟服務搶 ESP32 唯一連線，把監聽踢掉。
 *
 * 斷線時先 disconnect()、等 STATE_DISCONNECTED（或逾時）再 close()，
 * 避免立刻 disconnect+close 造成下一次 status 133。
 */
@SuppressLint("MissingPermission")
class BleController(
    private val context: Context,
    private val scanOnly: Boolean = false,
) {

    interface Listener {
        fun onStatus(text: String)
        fun onDeviceFound(device: BluetoothDevice, rssi: Int)
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected()
        fun onWriteResult(ok: Boolean)
        /** 掃描結束。預設不做事，避免把已連線蓋成未連線。 */
        fun onScanStopped() {}
    }

    var listener: Listener? = null

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var connected = false
    /** 這次連線嘗試或已連上；用來避免重複通知斷線。 */
    private var sessionOpen = false
    private var targetMac: String? = null
    private var awaitingDisconnectCallback = false
    private var pendingAction: (() -> Unit)? = null
    private var generation = 0
    private var continuation: Runnable? = null
    /** 已 close 的 GATT，遲到的 callback 不要當成新斷線。 */
    private val closedIds = ArrayDeque<Int>()

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true
    fun isConnected(): Boolean = connected

    /** 正在連、正在等斷線 callback，或剛 close 的安靜期。 */
    fun isLinkBusy(): Boolean =
        gatt != null || awaitingDisconnectCallback || continuation != null

    /** BLUETOOTH_CONNECT on API 31+; legacy BLUETOOTH below that. */
    fun hasConnectPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun startScan(timeoutMs: Long = 12_000L) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            listener?.onStatus("狀態：藍牙不可用")
            return
        }
        if (scanning) return
        scanning = true
        listener?.onStatus(context.getString(R.string.status_scanning))
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed({ stopScan() }, timeoutMs)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        listener?.onScanStopped()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName
            if (name != null && name.contains(BleConstants.DEVICE_NAME, ignoreCase = true)) {
                listener?.onDeviceFound(device, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onStatus("狀態：掃描失敗 ($errorCode)")
        }
    }

    /**
     * @return false 表示這次沒有開始連（掃描專用、MAC 無效）。
     * true 表示已連上、正在連，或已排程在舊 GATT 關閉後再連。
     */
    fun connect(mac: String): Boolean {
        if (scanOnly) {
            Log.w(TAG, "refusing connect on scan-only controller")
            return false
        }
        val device = try {
            adapter?.getRemoteDevice(mac)
        } catch (_: Exception) {
            null
        }
        if (device == null) {
            listener?.onStatus("狀態：無效 MAC")
            return false
        }
        stopScan()
        if (sameTarget(mac) && !awaitingDisconnectCallback && gatt != null) {
            // 已連上或這次嘗試還沒結束，不要再開第二條 GATT。
            return true
        }
        if (gatt == null && !awaitingDisconnectCallback && continuation != null) {
            // 剛 close，安靜期內不要取消延遲、立刻 connectGatt（容易 status 133）。
            Log.d(TAG, "connect coalesced during post-close quiet period")
            return true
        }
        val gen = ++generation
        cancelContinuation()
        enqueueAfterClose {
            if (gen != generation) return@enqueueAfterClose
            startConnect(device, mac)
        }
        return true
    }

    fun disconnect() {
        generation++
        cancelContinuation()
        handler.removeCallbacks(connectTimeout)
        if (!sessionOpen && gatt == null && !awaitingDisconnectCallback) return
        enqueueAfterClose { deliverDisconnected() }
    }

    fun writeCommand(cmd: Byte) {
        val g = gatt
        val c = characteristic
        if (g == null || c == null || !connected) {
            Log.w(TAG, "writeCommand skipped: not ready")
            listener?.onWriteResult(false)
            return
        }
        val payload = byteArrayOf(cmd)
        val writeType = if (
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        try {
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // SUCCESS == 0. 不要引用 BluetoothStatusCodes，minSdk 26 載入類別會崩。
                g.writeCharacteristic(c, payload, writeType) == 0
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = writeType
                    c.value = payload
                    g.writeCharacteristic(c)
                }
            }
            if (!queued) {
                Log.w(TAG, "writeCharacteristic rejected cmd=0x${"%02X".format(cmd)}")
                listener?.onWriteResult(false)
                return
            }
            Log.d(TAG, "write 0x${"%02X".format(cmd)} queued")
            listener?.onWriteResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
            listener?.onWriteResult(false)
        }
    }

    fun flashStart() = writeCommand(BleConstants.CMD_START)
    fun flashStop() = writeCommand(BleConstants.CMD_STOP)

    private fun sameTarget(mac: String): Boolean =
        mac.equals(targetMac, ignoreCase = true) && (connected || gatt != null)

    private fun startConnect(device: BluetoothDevice, mac: String) {
        targetMac = mac
        sessionOpen = true
        listener?.onStatus(context.getString(R.string.status_connecting))
        val opened = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed", e)
            null
        }
        gatt = opened
        if (opened == null) {
            failConnection("狀態：連線失敗")
            return
        }
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    /** 連線或探索失敗：關掉 GATT 並通知，讓服務排重連。 */
    private fun failConnection(reason: String) {
        Log.w(TAG, reason)
        listener?.onStatus(reason)
        handler.removeCallbacks(connectTimeout)
        if (gatt == null && !awaitingDisconnectCallback) {
            deliverDisconnected()
            return
        }
        enqueueAfterClose { deliverDisconnected() }
    }

    private fun deliverDisconnected() {
        handler.removeCallbacks(connectTimeout)
        val tell = sessionOpen
        sessionOpen = false
        connected = false
        characteristic = null
        targetMac = null
        if (!tell) return
        listener?.onStatus(context.getString(R.string.status_disconnected))
        listener?.onDisconnected()
    }

    /**
     * 有舊客戶端就先 disconnect，等 callback 再 close，然後才跑 [action]。
     * 沒有舊客戶端則立刻跑。
     */
    private fun enqueueAfterClose(action: () -> Unit) {
        val current = gatt
        if (current == null && !awaitingDisconnectCallback) {
            action()
            return
        }
        pendingAction = action
        if (awaitingDisconnectCallback) return
        awaitingDisconnectCallback = true
        connected = false
        characteristic = null
        handler.removeCallbacks(connectTimeout)
        try {
            current?.disconnect()
            handler.removeCallbacks(closeFallback)
            handler.postDelayed(closeFallback, CLOSE_WAIT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "disconnect() failed", e)
            finishCloseAndContinue()
        }
    }

    private val closeFallback = Runnable {
        if (!awaitingDisconnectCallback) return@Runnable
        Log.w(TAG, "no STATE_DISCONNECTED; closing GATT anyway")
        finishCloseAndContinue()
    }

    private val connectTimeout = Runnable {
        if (connected || gatt == null || awaitingDisconnectCallback) return@Runnable
        failConnection("狀態：連線逾時")
    }

    private fun finishCloseAndContinue() {
        handler.removeCallbacks(closeFallback)
        awaitingDisconnectCallback = false
        val doomed = gatt
        gatt = null
        characteristic = null
        connected = false
        targetMac = null
        retire(doomed)
        val next = pendingAction
        pendingAction = null
        if (next != null) {
            runAfterClose(next, closedGatt = doomed != null)
        }
    }

    private fun runAfterClose(action: () -> Unit, closedGatt: Boolean) {
        val gen = generation
        val task = Runnable {
            continuation = null
            if (gen != generation) return@Runnable
            action()
        }
        cancelContinuation()
        continuation = task
        val delay = if (closedGatt) POST_CLOSE_DELAY_MS else 0L
        if (delay == 0L) {
            task.run()
        } else {
            handler.postDelayed(task, delay)
        }
    }

    private fun cancelContinuation() {
        continuation?.let { handler.removeCallbacks(it) }
        continuation = null
    }

    private fun isRetired(g: BluetoothGatt): Boolean =
        closedIds.contains(System.identityHashCode(g))

    private fun retire(g: BluetoothGatt?) {
        if (g == null) return
        val id = System.identityHashCode(g)
        if (closedIds.contains(id)) return
        closedIds.addLast(id)
        while (closedIds.size > 16) closedIds.removeFirst()
        try {
            g.close()
        } catch (e: Exception) {
            Log.w(TAG, "gatt.close failed", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (isRetired(g)) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (g != gatt || awaitingDisconnectCallback) {
                            Log.w(TAG, "ignore stale CONNECTED status=$status")
                            if (g != gatt) retire(g)
                            return@post
                        }
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            failConnection("狀態：連線失敗 ($status)")
                            return@post
                        }
                        listener?.onStatus("狀態：已連線，探索服務…")
                        val started = try {
                            g.discoverServices()
                        } catch (e: Exception) {
                            Log.e(TAG, "discoverServices threw", e)
                            false
                        }
                        if (!started) {
                            failConnection("狀態：服務探索失敗")
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "STATE_DISCONNECTED status=$status")
                        if (awaitingDisconnectCallback && g == gatt) {
                            finishCloseAndContinue()
                            return@post
                        }
                        if (g != gatt) {
                            retire(g)
                            return@post
                        }
                        // 連結自己掉了（含 status 133）。已經是 DISCONNECTED，直接 close。
                        handler.removeCallbacks(connectTimeout)
                        connected = false
                        characteristic = null
                        targetMac = null
                        gatt = null
                        retire(g)
                        deliverDisconnected()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g != gatt || awaitingDisconnectCallback || isRetired(g)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection("狀態：服務探索失敗 ($status)")
                    return@post
                }
                val service = g.getService(BleConstants.SERVICE_UUID)
                val char = service?.getCharacteristic(BleConstants.CHAR_UUID)
                if (char == null) {
                    failConnection("狀態：找不到特徵值")
                    return@post
                }
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                handler.removeCallbacks(connectTimeout)
                characteristic = char
                connected = true
                listener?.onStatus(context.getString(R.string.status_connected))
                listener?.onConnected(g.device)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                if (g != gatt || !connected || isRetired(g)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "onCharacteristicWrite status=$status")
                    listener?.onWriteResult(false)
                }
            }
        }
    }

    fun release() {
        stopScan()
        generation++
        cancelContinuation()
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(closeFallback)
        sessionOpen = false
        pendingAction = null
        awaitingDisconnectCallback = false
        connected = false
        characteristic = null
        targetMac = null
        // 擁有者正在結束，不能再等 callback；立刻 close，避免下一條連線撞上沒關的客戶端。
        val doomed = gatt
        gatt = null
        retire(doomed)
    }

    companion object {
        private const val TAG = "BleController"
        private const val CLOSE_WAIT_MS = 2_000L
        private const val POST_CLOSE_DELAY_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
