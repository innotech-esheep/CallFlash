package com.esheep.callflash

import android.content.Context
import android.content.Intent

/**
 * 背景服務才是真正連著燈的那條 BLE。畫面要用這份狀態，
 * 不能拿本頁測試用的連線來顯示「未連線」。
 * owner 用來丟掉已銷毀服務遲到的回呼，避免把新連線蓋成未連線。
 */
object LampLink {
    const val ACTION = "com.esheep.callflash.LAMP_LINK"

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isConnecting: Boolean = false
        private set

    @Volatile
    var owner: Int = 0

    fun publish(context: Context, connected: Boolean, connecting: Boolean) {
        isConnected = connected
        isConnecting = connecting && !connected
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }
}
