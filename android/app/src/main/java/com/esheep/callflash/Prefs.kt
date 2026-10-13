package com.esheep.callflash

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences(BleConstants.PREFS_NAME, Context.MODE_PRIVATE)

    var mac: String?
        get() = sp.getString(BleConstants.PREF_MAC, null)
        set(value) = sp.edit().putString(BleConstants.PREF_MAC, value).apply()

    var deviceName: String?
        get() = sp.getString(BleConstants.PREF_NAME, null)
        set(value) = sp.edit().putString(BleConstants.PREF_NAME, value).apply()

    /**
     * 是否希望自動監聽。
     * 升級相容：舊版只有 MAC、沒有此鍵時，只要有記住設備就視為 true。
     */
    var monitorWanted: Boolean
        get() {
            if (!sp.contains(BleConstants.PREF_MONITOR)) {
                return hasSavedDevice()
            }
            return sp.getBoolean(BleConstants.PREF_MONITOR, false)
        }
        set(value) = sp.edit().putBoolean(BleConstants.PREF_MONITOR, value).apply()

    fun saveDevice(mac: String, name: String?) {
        sp.edit()
            .putString(BleConstants.PREF_MAC, mac)
            .putString(BleConstants.PREF_NAME, name ?: BleConstants.DEVICE_NAME)
            .putBoolean(BleConstants.PREF_MONITOR, true)
            .apply()
    }

    fun hasSavedDevice(): Boolean = !mac.isNullOrBlank()
}
