package com.esheep.callflash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 安靜檢查 [BleConstants.VERSION_CHECK_URL]。
 * 拿不到／沒有新版：不彈錯、不擋監聽。
 * 有新版：通知或回調，用戶點一下才下載並走系統安裝畫面（不靜默安裝）。
 *
 * version.json 範例：
 * {
 *   "versionCode": 3,
 *   "versionName": "1.2.0",
 *   "apkUrl": "https://esheep.net/callflash/CallFlash.apk",
 *   "notes": "自動連燈與開機自啟"
 * }
 */
class UpdateChecker(private val context: Context) {

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    interface Listener {
        fun onUpdateAvailable(info: VersionInfo)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun checkQuietly(listener: Listener? = null) {
        executor.execute {
            try {
                val info = fetchVersion() ?: return@execute
                val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkg.longVersionCode.toInt()
                } else {
                    pkg.versionCode
                }
                if (info.versionCode <= current) return@execute
                main.post {
                    listener?.onUpdateAvailable(info)
                    showUpdateNotification(info)
                }
            } catch (e: Exception) {
                Log.d(TAG, "update check skipped: ${e.message}")
            }
        }
    }

    fun downloadAndInstall(info: VersionInfo, onProgress: ((String) -> Unit)? = null) {
        executor.execute {
            try {
                main.post { onProgress?.invoke("正在下載更新…") }
                val file = downloadApk(info.apkUrl)
                main.post {
                    onProgress?.invoke("請在系統畫面確認安裝")
                    openInstaller(file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "download/install failed", e)
                main.post { onProgress?.invoke("下載失敗，請稍後再試") }
            }
        }
    }

    private fun fetchVersion(): VersionInfo? {
        val conn = (URL(BleConstants.VERSION_CHECK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val code = json.optInt("versionCode", -1)
            val url = json.optString("apkUrl", "")
            if (code < 0 || url.isBlank()) return null
            return VersionInfo(
                versionCode = code,
                versionName = json.optString("versionName", code.toString()),
                apkUrl = url,
                notes = json.optString("notes", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadApk(apkUrl: String): File {
        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "CallFlash-update.apk")
            conn.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun openInstaller(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun showUpdateNotification(info: VersionInfo) {
        createUpdateChannel()
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_SHOW_UPDATE
                putExtra(EXTRA_APK_URL, info.apkUrl)
                putExtra(EXTRA_VERSION_NAME, info.versionName)
                putExtra(EXTRA_NOTES, info.notes)
                putExtra(EXTRA_VERSION_CODE, info.versionCode)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (info.notes.isNotBlank()) {
            "v${info.versionName}：${info.notes}"
        } else {
            "新版本 v${info.versionName}，點一下下載安裝"
        }
        val n = NotificationCompat.Builder(context, BleConstants.NOTIF_UPDATE_CHANNEL_ID)
            .setContentTitle("來電閃燈有更新")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(BleConstants.NOTIF_UPDATE_ID, n)
    }

    private fun createUpdateChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BleConstants.NOTIF_UPDATE_CHANNEL_ID,
                "應用更新",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        const val ACTION_SHOW_UPDATE = "com.esheep.callflash.SHOW_UPDATE"
        const val EXTRA_APK_URL = "apk_url"
        const val EXTRA_VERSION_NAME = "version_name"
        const val EXTRA_NOTES = "notes"
        const val EXTRA_VERSION_CODE = "version_code"
    }
}
