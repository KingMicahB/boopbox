// BoopBox device test 1: which revision of the Audio Kit is this?
//
// Nothing wired, USB in the socket marked UART, Serial Monitor at 115200.
// Looks for the audio codec on the two pin pairs the kit has shipped with.
//
// PASS: "ES8388 on SDA 33 / SCL 32"  -> the wiring plan fits this board.
// STOP: "ES8388 on SDA 18 / SCL 23"  -> older revision, the encoder pins are taken.

#include <Wire.h>

const uint8_t ES8388_ADDR = 0x10;  // 0x11 on boards with the address pin pulled high
const uint8_t AC101_ADDR = 0x1A;   // the codec on the oldest kits

bool answers(uint8_t addr) {
  Wire.beginTransmission(addr);
  return Wire.endTransmission() == 0;
}

// Returns the codec's address on this pin pair, or 0.
uint8_t look(int sda, int scl, bool listEverything) {
  Wire.begin(sda, scl, 100000);
  Wire.setTimeOut(20);
  uint8_t codec = 0;
  if (listEverything) {
    for (uint8_t addr = 0x08; addr < 0x78; addr++) {
      if (!answers(addr)) continue;
      Serial.printf("  SDA %d / SCL %d: something answers at 0x%02X\n", sda, scl, addr);
    }
  }
  if (answers(ES8388_ADDR)) codec = ES8388_ADDR;
  else if (answers(ES8388_ADDR + 1)) codec = ES8388_ADDR + 1;
  else if (answers(AC101_ADDR)) codec = AC101_ADDR;
  Wire.end();
  return codec;
}

void setup() {
  Serial.begin(115200);
  delay(500);
}

void loop() {
  Serial.println();
  Serial.println("BoopBox test 1: I2C scan");

  uint8_t onNew = look(33, 32, true);
  uint8_t onOld = onNew ? 0 : look(18, 23, false);

  if (onNew == AC101_ADDR || onOld == AC101_ADDR) {
    Serial.println("STOP: this board has an AC101 codec, not an ES8388. The test sketches do not drive it.");
  } else if (onNew) {
    Serial.printf("PASS: ES8388 at 0x%02X on SDA 33 / SCL 32. The wiring plan fits this board.\n", onNew);
    if (onNew != ES8388_ADDR) Serial.println("NOTE: set ES8388_ADDR to 0x11 in the other sketches.");
  } else if (onOld) {
    Serial.printf("STOP: ES8388 at 0x%02X on SDA 18 / SCL 23. Older revision: IO18, IO23 and IO5 are\n", onOld);
    Serial.println("      used by the codec, so the encoder cannot go there. The pin map must change.");
  } else {
    Serial.println("FAIL: no codec found on either pin pair. Is this an ESP32-Audio-Kit?");
  }

  delay(5000);
}
