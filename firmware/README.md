# CallFlash firmware / 韌體

## Company / 公司

Innotech Electric Sheep Limited（創科電子羊有限公司），a company incorporated in the Macao SAR, China（澳門特別行政區註冊公司）。  
https://esheep.net · info@esheep.net

## Board / 板子

- Preferred: Seeed XIAO ESP32-C3 (Arduino board: `XIAO_ESP32C3`)
- LED data pin: D9 = GPIO9
- Library: Adafruit NeoPixel (Arduino Library Manager)

## Board support

Arduino IDE → Preferences → Additional boards URL:

`https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`

Then Boards Manager → install **esp32 by Espressif**.

## Upload / 燒錄

Use a data-capable USB-C cable. Upload speed 921600; if it fails, try 115200.
