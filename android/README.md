# CallFlash（來電閃燈）

**Innotech Electric Sheep Limited（創科電子羊有限公司）**  
A company incorporated in the Macao Special Administrative Region of the People’s Republic of China（中華人民共和國澳門特別行政區註冊公司）。  
Website: https://esheep.net · Email: info@esheep.net

來電時透過 BLE 控制 ESP32 閃燈的 Android 應用。

- **套件名稱**：`com.esheep.callflash`
- **適用機型**：黑鯊 4（JOYUI 13）、榮耀 Honor 90（MagicOS 9 / Android 15）及其他 Android 8.0+ 裝置
- **建置產出**：`app/build/outputs/apk/debug/app-debug.apk`
- **目前版本**：versionCode `2` / versionName `1.1.0`

## BLE 協定

| 項目 | 值 |
|------|-----|
| 廣播名稱 | `CallFlash` |
| Service UUID | `0000fff0-0000-1000-8000-00805f9b34fb` |
| Characteristic UUID | `0000fff1-0000-1000-8000-00805f9b34fb` |
| 寫入 0x01 | 開始閃燈 |
| 寫入 0x00 | 停止閃燈 |
| 寫入方式 | Write Without Response（優先） |

來電狀態：`RINGING` → `0x01`；`IDLE` / `OFFHOOK` → `0x00`；響鈴期間每 10 秒可重送 `0x01`。

## 自動連燈／自動監聽（1.1+）

- 記住上次選中的藍牙裝置（名稱與 MAC）。
- 選過設備後：開啟 App、系統拉起服務、手機重開（`BOOT_COMPLETED`）都會自動連燈並開始聽來電，不用再選、不用再按「開始監聽」。
- 前景服務 + 通知列長駐；`START_STICKY`；從最近任務滑掉時 `onTaskRemoved` 會盡量自己再拉起。
- **不保證**廠商殺後台一定能活下來（黑鯊／榮耀仍可能清掉），請關閉電池優化並開自啟動。
- 畫面以狀態顯示「已在聽／正在連燈」；「暫時停止監聽」會停到下次再開 App。

## 側載更新檢查

App 啟動時安靜讀取：

`https://esheep.net/callflash/version.json`

拿不到或沒有較新 `versionCode` 時**不彈錯、不擋監聽**。有新版時以通知／對話框提示，用戶點「下載安裝」後下載 APK 並開啟**系統安裝畫面**（不靜默覆蓋）。

### version.json 格式

```json
{
  "versionCode": 3,
  "versionName": "1.2.0",
  "apkUrl": "https://esheep.net/callflash/CallFlash.apk",
  "notes": "可選的更新說明"
}
```

| 欄位 | 必填 | 說明 |
|------|------|------|
| `versionCode` | 是 | 整數；大於本機 `versionCode` 才提示更新 |
| `versionName` | 建議 | 顯示用版本名 |
| `apkUrl` | 是 | APK 下載 HTTPS 網址 |
| `notes` | 否 | 更新說明 |

## 安裝（未知來源）

1. 將 `CallFlash-debug.apk` 傳到手機。
2. 開啟 APK → 若提示「禁止安裝未知來源應用」，允許此來源安裝。
3. 安裝完成後開啟「來電閃燈」。

## 首次設定

1. **授予權限**：電話狀態、藍牙掃描／連線、通知（Android 13+）。
2. 確認 ESP32 韌體已上電，廣播名稱為 `CallFlash`。
3. 點 **掃描設備**，在列表中點選設備（會記住 MAC，並自動開始監聽）。
4. 點 **測試閃燈**（應閃約 2 秒後停止）。
5. 點 **忽略電池優化**，允許本 App 不受電池優化限制。
6. 狀態列會出現常駐通知「來電閃燈監聽中」。

## 榮耀 / 黑鯊電池優化（重要）

1. **電池／休眠時關閉應用活動**：將「來電閃燈」設為允許或不限制。
2. **應用程式 → 來電閃燈 → 電池**：選 **不限制**。
3. **自動啟動**：開啟。
4. 多任務畫面可鎖定 App 卡片（仍可能被廠商清理；程式會盡量自己回來）。
5. 保持前台服務通知不要手動清掉。

## 建置（本機）

```bash
# Set JAVA_HOME (JDK 17) and ANDROID_HOME (Android SDK) first
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 疑難排解

- 掃描不到：確認藍牙已開、ESP32 在廣播、距離夠近。
- 測試無反應：確認已選對設備；檢查 UUID 是否與韌體一致。
- 來電不閃：確認通知仍在、狀態為「已在聽」；再檢查電池優化／自動啟動。
- 滑掉後失效：屬廠商限制；重開 App 或重開機會再自動拉起（若未按暫時停止）。
