package com.esheep.callflash

import java.util.UUID

object BleConstants {
    const val DEVICE_NAME = "CallFlash"
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val CHAR_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    const val CMD_START: Byte = 0x01
    const val CMD_STOP: Byte = 0x00
    const val PREFS_NAME = "callflash_prefs"
    const val PREF_MAC = "device_mac"
    const val PREF_NAME = "device_name"
    const val PREF_MONITOR = "monitor_wanted"
    const val NOTIF_CHANNEL_ID = "callflash_monitor"
    const val NOTIF_UPDATE_CHANNEL_ID = "callflash_update"
    const val NOTIF_ID = 1001
    const val NOTIF_UPDATE_ID = 1002
    const val RESEND_INTERVAL_MS = 10_000L
    const val TEST_FLASH_MS = 2_000L
    /** 側載更新檢查位址；拿不到或沒有新版就安靜跳過 */
    const val VERSION_CHECK_URL = "https://esheep.net/callflash/version.json"
}
