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

@SuppressLint("MissingPermission")
class BleController(private val context: Context) {

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

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var connected = false

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true
    fun isConnected(): Boolean = connected

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

    fun connect(mac: String) {
        val device = try {
            adapter?.getRemoteDevice(mac)
        } catch (_: Exception) {
            null
        }
        if (device == null) {
            listener?.onStatus("狀態：無效 MAC")
            return
        }
        stopScan()
        closeGatt()
        listener?.onStatus(context.getString(R.string.status_connecting))
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        closeGatt()
        connected = false
        characteristic = null
        listener?.onStatus(context.getString(R.string.status_disconnected))
        listener?.onDisconnected()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, payload, writeType)
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = writeType
                    c.value = payload
                    g.writeCharacteristic(c)
                }
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

    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        listener?.onStatus("狀態：已連線，探索服務…")
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connected = false
                        characteristic = null
                        listener?.onStatus(context.getString(R.string.status_disconnected))
                        listener?.onDisconnected()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener?.onStatus("狀態：服務探索失敗")
                    return@post
                }
                val service = g.getService(BleConstants.SERVICE_UUID)
                val char = service?.getCharacteristic(BleConstants.CHAR_UUID)
                if (char == null) {
                    listener?.onStatus("狀態：找不到特徵值")
                    return@post
                }
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                characteristic = char
                connected = true
                gatt = g
                listener?.onStatus(context.getString(R.string.status_connected))
                listener?.onConnected(g.device)
            }
        }
    }

    fun release() {
        stopScan()
        disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "BleController"
    }
}
