# CallFlash BLE Protocol (fixed)

- Advertise name: CallFlash
- Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb
- Characteristic UUID (writable): 0000fff1-0000-1000-8000-00805f9b34fb
- Payload: exactly 1 byte
  - 0x01 = start flash
  - 0x00 = stop flash
- App: on RINGING send 0x01 (once on enter; optional re-send every 10s); on OFFHOOK/IDLE/hangup send 0x00
- Target phone: Honor 90, MagicOS 9 / Android 15 (REA-AN00) — aggressive battery; need foreground service + battery optimization guidance in README
