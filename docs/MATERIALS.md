# Materials / 材料清單（BOM）

**Innotech Electric Sheep Limited（創科電子羊有限公司）**  
Macao SAR / 中華人民共和國澳門特別行政區 · https://esheep.net · info@esheep.net

Reference build used for CallFlash (USB-powered living-room lamp). Prices vary by seller — verify part numbers before ordering.  
以下為來電閃燈參考用料（USB 供電客廳燈）。價錢因店家而異，下單前請自行核對型號。

## Core / 核心

| Qty | Part (EN) | 名稱（中文） | Notes / 備註 |
|----:|-----------|--------------|--------------|
| 1 | Seeed Studio **XIAO ESP32-C3** | Seeed XIAO ESP32-C3 開發板 | BLE + USB-C；韌體板型選 `XIAO_ESP32C3` |
| 1 | **WS2812 / NeoPixel** LED strip (~1 m / ~60 LEDs) | WS2812 燈帶（約 1 米／約 60 燈） | 5 V；資料腳接 XIAO **D9（GPIO9）** |
| 1 | USB-C PD / 5 V power brick (≥2 A recommended) | USB-C 5 V 變壓器（建議 ≥2 A） | Prefer quality brand；長插電場景 |
| 1 | USB-C cable (data-capable for flashing) | 可傳資料 USB-C 線 | 燒錄韌體用；劣質線常只供電 |

## Power split (as built) / 供電（實作）

| Qty | Part (EN) | 名稱（中文） | Notes / 備註 |
|----:|-----------|--------------|--------------|
| 1 | USB-C breakout / dual-rail module (**5 V** + **3.3 V**) | Type-C 雙路電源模組（5 V／3.3 V） | **5 V → 燈帶**；**3.3 V → XIAO**（勿把燈帶 5 V 直灌 GPIO） |
| — | Common **GND** | 共地 | 模組 GND、XIAO GND、燈帶 GND 必須共地 |

> Earlier plans with a bare 18650 + boost board were set aside for the simpler USB-C dual-rail module.  
> 早前「18650＋升壓」方案已擱置，改用較簡單嘅 Type-C 雙路模組。

## Wiring summary / 接線摘要

| From | To | Wire |
|------|-----|------|
| Module 5 V | LED strip VCC (often red) | Power |
| Module 3.3 V | XIAO 3V3 | Power |
| Module GND | XIAO GND + strip GND (often white/black) | Ground |
| XIAO **D9** | Strip DIN (data, often yellow/green) | Signal |

Boot behaviour of the reference firmware: solid green on power-up; call flash cycles red → green → blue.

## Optional / 選配

| Qty | Part | 用途 |
|----:|------|------|
| 1 | Enclosure / 3D-printed or store-bought case | 外殼、固定於床頭／客廳 |
| — | Heat-shrink, zip ties, double-sided tape | 絕緣、束線、固定 |
| — | Breadboard + Dupont wires (prototype only) | 試作；量產宜焊線／端子 |

## Software / 軟件

| Item | Notes |
|------|-------|
| Arduino IDE + esp32 by Espressif + Adafruit NeoPixel | See `firmware/README.md` |
| Android 8+ phone with CallFlash APK | Package `com.esheep.callflash` |
| Battery unrestricted / autostart | Critical on Honor / Black Shark OEMs |

## Safety / 安全

- Low-voltage **USB only**. Do not connect to mains AC.  
  僅低壓 USB，**勿接市電**。
- Not a medical device.  
  **非醫療器材**。
- Unplug before soldering or rearranging wires.  
  焊接／改線前先斷電。
