# CallFlash（來電閃燈）

Open-source BLE flashing light for hard-of-hearing users. An Android phone detects an incoming call and tells a nearby ESP32 lamp to flash.

開源藍牙閃燈：手機偵測到來電，經 BLE 通知附近 ESP32 燈具閃爍，方便聽障或聽力欠佳人士察覺來電。

## Company / 公司

| | |
|---|---|
| English | **Innotech Electric Sheep Limited** |
| 中文 | **創科電子羊有限公司** |
| Jurisdiction / 註冊地 | Macao Special Administrative Region of the People’s Republic of China（中華人民共和國澳門特別行政區） |
| Website / 網站 | https://esheep.net |
| Email / 電郵 | info@esheep.net |

This project is published under the company name. The GitHub organization account is maintained by the company.

本專案以公司名義公開。GitHub 組織帳號由本公司維護。

## What’s inside / 內容

- `firmware/CallFlash/CallFlash.ino` — Seeed XIAO ESP32-C3 firmware (LED data pin D9 / GPIO9)
- `android/` — Android companion app (`com.esheep.callflash`)
- `docs/PROTOCOL.md` — BLE protocol

## License / 授權

MIT. Copyright © 2026 Innotech Electric Sheep Limited (創科電子羊有限公司), Macao SAR.

See [LICENSE](LICENSE).

## Disclaimer / 免責

This software and hardware design are provided as-is for accessibility assistance. They are not a medical device and do not replace professional hearing care or emergency alert systems.

本軟件及硬體設計按現況提供，僅作無障礙輔助，並非醫療器材，亦不能取代專業聽力照護或緊急警報系統。
