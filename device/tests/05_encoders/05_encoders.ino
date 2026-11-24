// BoopBox device test 5: both knobs, through the MCP23017 port expander.
//
// Expander: SDA -> IO18, SCL -> IO23, INTA -> IO5, VCC -> 3V3, GND -> GND.
// Knob 1 (volume):         CLK -> GPA0, DT -> GPA1, SW -> GPA2
// Knob 2 (back / forward): CLK -> GPA3, DT -> GPA4, SW -> GPA5
// Both knobs: + -> 3V3 (never 5 V), GND -> GND. Serial Monitor at 115200.
//
// PASS: "Expander found at 0x20", then "Knob 1: +1 (total 3)" for every click of a knob and
//       "Knob 1: pressed" once per press. Same for knob 2.
// FAIL: "Expander: NOT FOUND" -> tie its RESET pin to 3V3 and its three address pins to GND,
//       check SDA / SCL are not swapped.
//       A knob counting backwards has its CLK and DT wires swapped.

#include <Wire.h>

const int PIN_SDA = 18, PIN_SCL = 23, PIN_INT = 5;
const uint8_t PN532_I2C_ADDR = 0x24;  // reader B sits on the same two wires

// MCP23017 registers (the chip's power-on layout)
const uint8_t REG_IODIRA = 0x00, REG_IODIRB = 0x01, REG_GPINTENA = 0x04, REG_INTCONA = 0x08;
const uint8_t REG_IOCON = 0x0A, REG_GPPUA = 0x0C, REG_GPPUB = 0x0D, REG_INTCAPA = 0x10, REG_GPIOA = 0x12;

struct Knob {
  uint8_t clkBit, dtBit, swBit;
  uint8_t state;       // last CLK / DT levels, two bits
  int8_t travel;       // quarter-steps since the last click position
  bool down;           // button held
  uint32_t downChangedMs;
  int32_t total;
};

Knob knobs[2] = {
  {0, 1, 2, 3, 0, false, 0, 0},
  {3, 4, 5, 3, 0, false, 0, 0},
};

TwoWire &Bus = Wire1;  // Wire (the other I2C port) belongs to the codec
uint8_t expander = 0;  // its address once found
bool interruptSeen = false;  // IO5 has gone low at least once

bool regWrite(uint8_t reg, uint8_t value) {
  Bus.beginTransmission(expander);
  Bus.write(reg);
  Bus.write(value);
  return Bus.endTransmission() == 0;
}

// Reads `n` registers in a row starting at `reg`.
bool regRead(uint8_t reg, uint8_t *out, uint8_t n) {
  Bus.beginTransmission(expander);
  Bus.write(reg);
  if (Bus.endTransmission() != 0) return false;
  if (Bus.requestFrom(expander, (size_t)n, true) != n) return false;
  for (uint8_t i = 0; i < n; i++) out[i] = Bus.read();
  return true;
}

// The expander can be strapped to 0x20..0x27. 0x24 is reader B, so that one is never it.
bool findExpander() {
  for (uint8_t addr = 0x20; addr <= 0x27; addr++) {
    if (addr == PN532_I2C_ADDR) continue;
    expander = addr;
    uint8_t back = 0;
    // A real MCP23017 remembers what is written to its pull-up register.
    if (regWrite(REG_GPPUA, 0x5A) && regRead(REG_GPPUA, &back, 1) && back == 0x5A) return true;
  }
  expander = 0;
  return false;
}

bool expanderBegin() {
  bool ok = true;
  ok &= regWrite(REG_IOCON, 0x44);     // one interrupt wire for both ports, open drain
  ok &= regWrite(REG_IODIRA, 0xFF);    // port A: all inputs
  ok &= regWrite(REG_IODIRB, 0xFF);    // port B: all inputs
  ok &= regWrite(REG_GPPUA, 0xFF);     // pull-ups on, so nothing floats
  ok &= regWrite(REG_GPPUB, 0xFF);
  ok &= regWrite(REG_INTCONA, 0x00);   // interrupt on any change
  ok &= regWrite(REG_GPINTENA, 0x3F);  // ...of the six knob pins
  uint8_t now = 0;
  ok &= regRead(REG_GPIOA, &now, 1);   // reading clears any pending interrupt
  for (Knob &k : knobs) k.state = (((now >> k.clkBit) & 1) << 1) | ((now >> k.dtBit) & 1);
  return ok;
}

void onTurn(int knob, int direction) {
  knobs[knob].total += direction;
  Serial.printf("Knob %d: %+d (total %ld)\n", knob + 1, direction, (long)knobs[knob].total);
  static int turns = 0;
  if (++turns == 8 && !interruptSeen) {
    Serial.println("NOTE: the knobs work, but IO5 never went low. Check the INTA wire: without it");
    Serial.println("      fast turns can be missed.");
  }
}

void onPress(int knob) {
  Serial.printf("Knob %d: pressed\n", knob + 1);
}

// Feeds one snapshot of port A to both knobs.
void feed(uint8_t port) {
  // Which way one quarter-step went, indexed by (previous state << 2) | new state.
  static const int8_t QUARTER[16] = {0, -1, 1, 0, 1, 0, 0, -1, -1, 0, 0, 1, 0, 1, -1, 0};
  uint32_t nowMs = millis();
  for (int i = 0; i < 2; i++) {
    Knob &k = knobs[i];
    uint8_t s = (((port >> k.clkBit) & 1) << 1) | ((port >> k.dtBit) & 1);
    k.travel += QUARTER[(k.state << 2) | s];
    k.state = s;
    if (s == 3) {  // both contacts open: the knob is resting in a click position
      if (k.travel >= 2) onTurn(i, +1);
      else if (k.travel <= -2) onTurn(i, -1);
      k.travel = 0;
    }
    bool down = ((port >> k.swBit) & 1) == 0;
    if (down != k.down && nowMs - k.downChangedMs > 30) {
      k.down = down;
      k.downChangedMs = nowMs;
      if (down) onPress(i);
    }
  }
}

// Call often. Reads the expander when it signals a change, and every 20 ms regardless.
void serviceKnobs() {
  static uint32_t lastReadMs = 0;
  bool signalled = digitalRead(PIN_INT) == LOW;
  if (!signalled && millis() - lastReadMs < 20) return;
  lastReadMs = millis();
  uint8_t r[3];  // INTCAPA (port A at the moment of the change), INTCAPB, GPIOA (port A now)
  if (!regRead(REG_INTCAPA, r, 3)) return;
  if (signalled) {
    interruptSeen = true;
    feed(r[0]);
  }
  feed(r[2]);
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("BoopBox test 5: knobs");

  pinMode(PIN_INT, INPUT_PULLUP);
  Bus.begin(PIN_SDA, PIN_SCL, 100000);
  Bus.setTimeOut(50);

  if (!findExpander()) {
    Serial.println("Expander: NOT FOUND. Tie RESET to 3V3 and the three address pins to GND;");
    Serial.println("          check SDA / SCL are not swapped. Then press RST on the kit.");
    return;
  }
  Serial.printf("Expander found at 0x%02X\n", expander);
  if (!expanderBegin()) Serial.println("Expander: answered, but a setting did not take");
  Serial.printf("Interrupt wire (IO5) is %s\n",
                digitalRead(PIN_INT) == HIGH ? "resting high, as it should" : "LOW: check the INTA wire");
  Serial.println("Turn and press the knobs...");
}

void loop() {
  if (expander == 0) {
    delay(1000);
    return;
  }
  serviceKnobs();
}
