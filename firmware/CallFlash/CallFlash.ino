#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Adafruit_NeoPixel.h>

#define LED_PIN   9  // XIAO D9 = GPIO9
#define LED_COUNT 60
#define FLASH_MS  400
#define IDLE_OFF_MS 60000UL
#define LAMP_LEVEL 100

static const char* DEVICE_NAME = "CallFlash";
static const char* SVC_UUID = "0000fff0-0000-1000-8000-00805f9b34fb";
static const char* CHR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb";

Adafruit_NeoPixel strip(LED_COUNT);
volatile bool flashing = false;
volatile unsigned long lastCmdMs = 0;
volatile uint8_t colorStep = 0;

void setAll(uint8_t r, uint8_t g, uint8_t b) {
  for (int i = 0; i < LED_COUNT; i++) strip.setPixelColor(i, r, g, b);
  strip.show();
}

void stopFlash() {
  flashing = false;
  colorStep = 0;
  setAll(0, 0, 0);
}

void showFlashColor() {
  uint8_t step = colorStep;
  if (step == 0) setAll(LAMP_LEVEL, 0, 0);
  else if (step == 1) setAll(0, LAMP_LEVEL, 0);
  else setAll(0, 0, LAMP_LEVEL);
  colorStep = (step + 1) % 3;
}

class CmdCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue();
    if (v.isEmpty()) return;
    lastCmdMs = millis();
    if ((uint8_t)v[0] == 0x01) {
      colorStep = 0;
      flashing = true;
    } else if ((uint8_t)v[0] == 0x00) {
      stopFlash();
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override { lastCmdMs = millis(); }
  void onDisconnect(BLEServer* s) override {
    stopFlash();
    s->getAdvertising()->start();
  }
};

void setup() {
  strip.begin();
  strip.setPin(LED_PIN);
  setAll(0, 80, 0);

  BLEDevice::init(DEVICE_NAME);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* svc = server->createService(SVC_UUID);
  BLECharacteristic* chr = svc->createCharacteristic(
      CHR_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  chr->setCallbacks(new CmdCallbacks());
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_UUID);
  adv->setScanResponse(true);
  adv->start();
  lastCmdMs = millis();
}

void loop() {
  if (flashing) {
    showFlashColor();
    delay(FLASH_MS);
    if (millis() - lastCmdMs > IDLE_OFF_MS) stopFlash();
  }
}
