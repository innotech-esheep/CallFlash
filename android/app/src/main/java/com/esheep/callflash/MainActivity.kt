package com.esheep.callflash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSelected: TextView
    private lateinit var tvMonitorStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var btnTest: Button
    private lateinit var btnStop: Button
    private lateinit var btnBattery: Button
    private lateinit var listDevices: ListView

    private lateinit var prefs: Prefs
    private lateinit var ble: BleController
    private lateinit var updateChecker: UpdateChecker
    private val handler = Handler(Looper.getMainLooper())

    private data class FoundDevice(val mac: String, val name: String, val rssi: Int) {
        override fun toString(): String = "$name  [$mac]  ${rssi}dBm"
    }

    private val found = linkedMapOf<String, FoundDevice>()
    private lateinit var listAdapter: ArrayAdapter<FoundDevice>
    private var pendingUpdate: UpdateChecker.VersionInfo? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "請授予電話／藍牙／通知權限", Toast.LENGTH_LONG).show()
        } else {
            maybeAutoStartMonitor()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        ble = BleController(this)
        updateChecker = UpdateChecker(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvSelected = findViewById(R.id.tvSelected)
        tvMonitorStatus = findViewById(R.id.tvMonitorStatus)
        btnScan = findViewById(R.id.btnScan)
        btnTest = findViewById(R.id.btnTest)
        btnStop = findViewById(R.id.btnStop)
        btnBattery = findViewById(R.id.btnBattery)
        listDevices = findViewById(R.id.listDevices)

        listAdapter = ArrayAdapter(this, R.layout.item_device, android.R.id.text1, mutableListOf())
        listDevices.adapter = listAdapter

        refreshSelectedLabel()
        refreshMonitorStatus()
        refreshLinkStatus()
        requestNeededPermissions()

        ble.listener = object : BleController.Listener {
            override fun onStatus(text: String) {
                runOnUiThread {
                    val idle = text == getString(R.string.status_idle)
                    val dropped = text == getString(R.string.status_disconnected)
                    if (idle || dropped || (LampLink.isConnected && text != getString(R.string.status_scanning))) {
                        refreshLinkStatus()
                    } else {
                        tvStatus.text = text
                    }
                }
            }

            override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
                @SuppressLint("MissingPermission")
                val name = device.name ?: BleConstants.DEVICE_NAME
                val mac = device.address
                runOnUiThread {
                    found[mac] = FoundDevice(mac, name, rssi)
                    listAdapter.clear()
                    listAdapter.addAll(found.values)
                    listAdapter.notifyDataSetChanged()
                }
            }

            override fun onConnected(device: BluetoothDevice) {
                runOnUiThread {
                    tvStatus.text = getString(R.string.status_connected)
                }
            }

            override fun onDisconnected() {
                runOnUiThread { refreshLinkStatus() }
            }

            override fun onScanStopped() {
                runOnUiThread { refreshLinkStatus() }
            }

            override fun onWriteResult(ok: Boolean) {
                if (!ok) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "寫入失敗，請確認已連線", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnScan.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            if (!ble.isBluetoothOn()) {
                Toast.makeText(this, "請先開啟藍牙", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            found.clear()
            listAdapter.clear()
            listAdapter.notifyDataSetChanged()
            ble.startScan()
        }

        listDevices.setOnItemClickListener { _, _, position, _ ->
            val item = listAdapter.getItem(position) ?: return@setOnItemClickListener
            prefs.saveDevice(item.mac, item.name)
            refreshSelectedLabel()
            ble.stopScan()
            // 選好就自動開始聽，不用再按「開始監聽」
            maybeAutoStartMonitor()
            Toast.makeText(this, "已記住 ${item.name}，正在自動連燈並監聽", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            val mac = prefs.mac
            if (mac.isNullOrBlank()) {
                Toast.makeText(this, "請先選擇設備", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 測試用本頁 BLE；服務也會連同一盞燈
            if (!ble.isConnected()) {
                ble.connect(mac)
                handler.postDelayed({ doTestFlash() }, 2500)
            } else {
                doTestFlash()
            }
        }

        btnStop.setOnClickListener {
            stopMonitor()
        }

        btnBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        handleUpdateIntent(intent)
        // 安靜檢查更新，失敗不擋監聽
        updateChecker.checkQuietly(object : UpdateChecker.Listener {
            override fun onUpdateAvailable(info: UpdateChecker.VersionInfo) {
                pendingUpdate = info
                showUpdateDialog(info)
            }
        })

        maybeAutoStartMonitor()
        refreshLinkStatus()
    }

    private val lampReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLinkStatus()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            lampReceiver,
            IntentFilter(LampLink.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshLinkStatus()
    }

    override fun onStop() {
        try {
            unregisterReceiver(lampReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshMonitorStatus()
        refreshLinkStatus()
        // 回到畫面若已記住設備，確保服務在跑
        maybeAutoStartMonitor()
        refreshLinkStatus()
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.action != UpdateChecker.ACTION_SHOW_UPDATE) return
        val code = intent.getIntExtra(UpdateChecker.EXTRA_VERSION_CODE, -1)
        val url = intent.getStringExtra(UpdateChecker.EXTRA_APK_URL) ?: return
        val name = intent.getStringExtra(UpdateChecker.EXTRA_VERSION_NAME) ?: code.toString()
        val notes = intent.getStringExtra(UpdateChecker.EXTRA_NOTES) ?: ""
        if (code < 0 || url.isBlank()) return
        val info = UpdateChecker.VersionInfo(code, name, url, notes)
        pendingUpdate = info
        showUpdateDialog(info)
    }

    private fun showUpdateDialog(info: UpdateChecker.VersionInfo) {
        val msg = buildString {
            append("發現新版本 v${info.versionName}")
            if (info.notes.isNotBlank()) {
                append("\n\n")
                append(info.notes)
            }
            append("\n\n點「下載安裝」後會開啟系統安裝畫面，不會靜默覆蓋。")
        }
        AlertDialog.Builder(this)
            .setTitle("有可用更新")
            .setMessage(msg)
            .setPositiveButton("下載安裝") { _, _ ->
                updateChecker.downloadAndInstall(info) { status ->
                    Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("稍後", null)
            .show()
    }

    private fun doTestFlash() {
        ble.flashStart()
        Toast.makeText(this, "測試閃燈 2 秒…", Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            ble.flashStop()
        }, BleConstants.TEST_FLASH_MS)
    }

    private fun maybeAutoStartMonitor() {
        if (!prefs.hasSavedDevice()) {
            refreshMonitorStatus()
            return
        }
        if (!hasEssentialPermissions()) {
            refreshMonitorStatus()
            return
        }
        CallMonitorService.startIfWanted(this)
        refreshMonitorStatus()
    }

    private fun stopMonitor() {
        prefs.monitorWanted = false
        val intent = Intent(this, CallMonitorService::class.java).setAction(CallMonitorService.ACTION_STOP)
        startService(intent)
        stopService(Intent(this, CallMonitorService::class.java))
        refreshMonitorStatus()
        Toast.makeText(this, "已暫時停止；再開 App 或重開機會自動恢復", Toast.LENGTH_LONG).show()
    }

    private fun refreshMonitorStatus() {
        val text = when {
            !prefs.hasSavedDevice() -> getString(R.string.monitor_need_device)
            isServiceRunning() -> getString(R.string.monitor_listening)
            prefs.monitorWanted -> getString(R.string.monitor_connecting)
            else -> getString(R.string.monitor_paused)
        }
        tvMonitorStatus.text = text
        btnStop.isEnabled = prefs.hasSavedDevice()
    }

    /**
     * 連線狀態以背景服務為準。已連上就寫已連線，不要因為本頁沒有自己的 GATT 而顯示未連線。
     */
    private fun refreshLinkStatus() {
        if (!::tvStatus.isInitialized || !::prefs.isInitialized) return
        val activityConnected = ::ble.isInitialized && ble.isConnected()
        tvStatus.text = when {
            LampLink.isConnected || activityConnected -> getString(R.string.status_connected)
            LampLink.isConnecting -> getString(R.string.status_connecting)
            prefs.hasSavedDevice() && prefs.monitorWanted -> getString(R.string.status_connecting)
            else -> getString(R.string.status_idle)
        }
    }

    private fun refreshSelectedLabel() {
        val mac = prefs.mac
        val name = prefs.deviceName
        tvSelected.text = if (mac.isNullOrBlank()) {
            getString(R.string.no_device)
        } else {
            getString(R.string.selected_device, "$name ($mac)")
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        needed += Manifest.permission.READ_PHONE_STATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasEssentialPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) return false
        }
        return true
    }

    private fun ensurePermissions(): Boolean {
        if (!hasEssentialPermissions()) {
            requestNeededPermissions()
            Toast.makeText(this, "請先允許權限", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已忽略電池優化", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "請手動到設定關閉電池優化", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(50).any { it.service.className == CallMonitorService::class.java.name }
    }

    override fun onDestroy() {
        // 服務自己管 BLE；本頁測試連線可釋放
        ble.stopScan()
        ble.release()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
