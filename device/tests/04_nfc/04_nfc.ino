// BoopBox device test 4: both NFC readers.
//
// Reader A (card slot):     switches OFF OFF, TXD -> IO19, RXD -> IO22      (serial / HSU)
// Reader B (sticker spot):  switches ON  OFF, SDA -> IO18, SCL -> IO23      (I2C)
// Serial Monitor at 115200. Works with only reader A wired, to test one at a time.
//
// PASS: "Reader A: found PN532 ..." and "Reader B: found PN532 ...", then
//       "A: card on  04A1B2C3" / "A: card off" as cards come and go, same for B.
//       The UID is printed the way the phone app stores it.
// FAIL: "Reader A: NO ANSWER" -> swap the IO19 / IO22 wires, check its switches are OFF OFF.
//       "Reader B: NO ANSWER" -> check its switch 1 is ON, swap the SDA / SCL wires.

#include <Wire.h>

const int PIN_A_RX = 19, PIN_A_TX = 22;  // ESP32 side: RX listens to the reader's TXD
const int PIN_B_SDA = 18, PIN_B_SCL = 23;
const uint8_t PN532_I2C_ADDR = 0x24;

// Only one reader's field on at a time, as the real firmware will do.
const bool ALTERNATE_FIELDS = true;

struct Reader {
  char name;
  bool i2c;
  bool found;
  uint8_t uid[10];
  uint8_t uidLen;
  uint8_t misses;
};

Reader readerA = {'A', false, false, {0}, 0, 0};
Reader readerB = {'B', true, false, {0}, 0, 0};

HardwareSerial &NfcSerial = Serial1;
TwoWire &NfcWire = Wire1;  // Wire (the other I2C port) belongs to the codec

// ---------------------------------------------------------------- PN532 frames

const uint8_t PN532_ACK[6] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00};
const uint8_t PN532_NACK[6] = {0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00};

// Looks for one PN532 frame in raw[0..n). Copies its data (starting with the 0xD5 marker) to out.
// Returns the data length, 0 for an ACK frame, -1 for a damaged frame, -2 if more bytes are needed.
int parseFrame(const uint8_t *raw, int n, uint8_t *out, int maxOut) {
  int i = 0;
  while (i + 1 < n && !(raw[i] == 0x00 && raw[i + 1] == 0xFF)) i++;
  if (i + 3 >= n) return -2;
  uint8_t len = raw[i + 2], lcs = raw[i + 3];
  if (len == 0x00 && lcs == 0xFF) return 0;
  if ((uint8_t)(len + lcs) != 0) return -1;
  if (i + 4 + len + 1 > n) return -2;
  uint8_t sum = 0;
  for (int k = 0; k <= len; k++) sum += raw[i + 4 + k];  // data + its checksum byte
  if (sum != 0) return -1;
  int copy = len < maxOut ? len : maxOut;
  memcpy(out, raw + i + 4, copy);
  return copy;
}

void sendBytes(Reader &r, const uint8_t *bytes, int n) {
  if (r.i2c) {
    NfcWire.beginTransmission(PN532_I2C_ADDR);
    NfcWire.write(bytes, n);
    NfcWire.endTransmission();
  } else {
    NfcSerial.write(bytes, n);
    NfcSerial.flush();
  }
}

// Over I2C the reader says when it has something: one status byte, 0x01 = ready.
bool i2cReady() {
  if (NfcWire.requestFrom(PN532_I2C_ADDR, (size_t)1, true) != 1) return false;
  return (NfcWire.read() & 0x01) != 0;
}

// Waits until the reader is ready, then reads `want` bytes (after the status byte) into raw.
// Returns the number read, or -1 if the reader never became ready.
int i2cRead(uint8_t *raw, uint8_t want, uint32_t timeoutMs) {
  uint32_t start = millis();
  delay(1);
  while (!i2cReady()) {
    if (millis() - start > timeoutMs) return -1;
    delay(3);
  }
  size_t got = NfcWire.requestFrom(PN532_I2C_ADDR, (size_t)(want + 1), true);
  if (got > 0) NfcWire.read();  // the status byte again
  int n = 0;
  while (NfcWire.available() && n < want) raw[n++] = NfcWire.read();
  return n;
}

// Waits for one frame from the reader: its acknowledgement if `ack`, otherwise its answer.
// Same return values as parseFrame, with -2 = nothing in time.
int readFrame(Reader &r, uint8_t *out, int maxOut, uint32_t timeoutMs, bool ack) {
  uint8_t raw[48];
  int n = 0;
  if (r.i2c) {
    if (ack) {
      n = i2cRead(raw, 6, timeoutMs);
      return n < 0 ? -2 : parseFrame(raw, n, out, maxOut);
    }
    // Read the frame's first five bytes to learn its length, ask the reader to send the frame
    // again (a NACK does that), then read exactly the whole frame.
    n = i2cRead(raw, 5, timeoutMs);
    if (n < 0) return -2;
    if (n < 5 || raw[0] != 0x00 || raw[1] != 0x00 || raw[2] != 0xFF) return -1;
    if ((uint8_t)(raw[3] + raw[4]) != 0) return -1;
    int total = 5 + raw[3] + 2;
    if (total > (int)sizeof raw) return -1;
    sendBytes(r, PN532_NACK, sizeof PN532_NACK);
    n = i2cRead(raw, total, 100);
    return n < 0 ? -2 : parseFrame(raw, n, out, maxOut);
  }
  uint32_t start = millis();
  for (;;) {
    while (NfcSerial.available() && n < (int)sizeof raw) {
      raw[n++] = NfcSerial.read();
      int result = parseFrame(raw, n, out, maxOut);
      if (result != -2) return result;
    }
    if (millis() - start > timeoutMs || n >= (int)sizeof raw) return -2;
    delay(1);
  }
}

// Sends one command and waits for its answer. Returns the number of answer bytes copied to
// `answer` (after the two-byte "answer to command X" header), or -1.
int command(Reader &r, const uint8_t *cmd, uint8_t n, uint8_t *answer, int maxAnswer, uint32_t timeoutMs) {
  uint8_t frame[40], data[40];
  if (n + 8 > (int)sizeof frame) return -1;
  uint8_t len = n + 1;
  uint8_t sum = 0xD4;
  int k = 0;
  frame[k++] = 0x00;
  frame[k++] = 0x00;
  frame[k++] = 0xFF;
  frame[k++] = len;
  frame[k++] = (uint8_t)(0x100 - len);
  frame[k++] = 0xD4;  // "from the host"
  for (uint8_t i = 0; i < n; i++) {
    frame[k++] = cmd[i];
    sum += cmd[i];
  }
  frame[k++] = (uint8_t)(0x100 - sum);
  frame[k++] = 0x00;

  if (!r.i2c) {
    while (NfcSerial.available()) NfcSerial.read();
  }
  sendBytes(r, frame, k);

  if (readFrame(r, data, sizeof data, 100, true) != 0) return -1;  // the reader must acknowledge first
  int got = readFrame(r, data, sizeof data, timeoutMs, false);
  if (got == -2) sendBytes(r, PN532_ACK, sizeof PN532_ACK);  // no answer in time: cancel the command
  if (got < 2 || data[0] != 0xD5 || data[1] != (uint8_t)(cmd[0] + 1)) return -1;
  int copy = got - 2 < maxAnswer ? got - 2 : maxAnswer;
  if (copy > 0) memcpy(answer, data + 2, copy);
  return copy;
}

// ---------------------------------------------------------------- PN532 commands

void wake(Reader &r) {
  if (r.i2c) return;  // over I2C the reader wakes when addressed
  static const uint8_t wakeUp[] = {0x55, 0x55, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  NfcSerial.write(wakeUp, sizeof wakeUp);
  NfcSerial.flush();
  delay(20);
}

bool readerBegin(Reader &r) {
  uint8_t answer[8];
  r.found = false;
  for (int attempt = 0; attempt < 3 && !r.found; attempt++) {
    wake(r);
    static const uint8_t getFirmware[] = {0x02};
    if (command(r, getFirmware, 1, answer, sizeof answer, 200) >= 4 && answer[0] == 0x32) {
      Serial.printf("Reader %c: found PN532, firmware %u.%u\n", r.name, answer[1], answer[2]);
      r.found = true;
    }
  }
  if (!r.found) return false;
  static const uint8_t normalMode[] = {0x14, 0x01, 0x14, 0x01};      // SAMConfiguration
  static const uint8_t fewRetries[] = {0x32, 0x05, 0xFF, 0x01, 0x02};  // give up quickly when no card
  bool ok = command(r, normalMode, sizeof normalMode, answer, sizeof answer, 200) >= 0;
  ok &= command(r, fewRetries, sizeof fewRetries, answer, sizeof answer, 200) >= 0;
  if (!ok) Serial.printf("Reader %c: answered, but refused its settings\n", r.name);
  return ok;
}

// One look for a card. True with its UID if one is there.
bool readCard(Reader &r, uint8_t *uid, uint8_t &uidLen) {
  static const uint8_t findCard[] = {0x4A, 0x01, 0x00};  // one ISO 14443-A card
  uint8_t answer[24];
  int n = command(r, findCard, sizeof findCard, answer, sizeof answer, 400);
  // answer: count, target number, SENS_RES (2), SEL_RES, UID length, UID...
  if (n < 6 || answer[0] != 1) return false;
  uidLen = answer[5];
  if (uidLen == 0 || uidLen > 10 || 6 + uidLen > n) return false;
  memcpy(uid, answer + 6, uidLen);
  return true;
}

void fieldOff(Reader &r) {
  static const uint8_t rfOff[] = {0x32, 0x01, 0x00};
  uint8_t answer[4];
  command(r, rfOff, sizeof rfOff, answer, sizeof answer, 100);
}

// ---------------------------------------------------------------- the test

void printUid(const uint8_t *uid, uint8_t len) {
  for (uint8_t i = 0; i < len; i++) Serial.printf("%02X", uid[i]);
}

void poll(Reader &r) {
  if (!r.found) return;
  uint8_t uid[10], len = 0;
  bool present = readCard(r, uid, len);
  if (ALTERNATE_FIELDS) fieldOff(r);

  if (present) {
    r.misses = 0;
    if (len != r.uidLen || memcmp(uid, r.uid, len) != 0) {
      memcpy(r.uid, uid, len);
      r.uidLen = len;
      Serial.printf("%c: card on  ", r.name);
      printUid(uid, len);
      Serial.println();
    }
  } else if (r.uidLen != 0 && ++r.misses >= 3) {  // three empty looks in a row = really gone
    r.uidLen = 0;
    Serial.printf("%c: card off\n", r.name);
  }
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("BoopBox test 4: NFC readers");

  NfcSerial.begin(115200, SERIAL_8N1, PIN_A_RX, PIN_A_TX);
  NfcWire.begin(PIN_B_SDA, PIN_B_SCL, 100000);
  NfcWire.setTimeOut(50);

  if (!readerBegin(readerA)) {
    Serial.println("Reader A: NO ANSWER. Swap the IO19 / IO22 wires; check its switches are OFF OFF.");
  }
  if (!readerBegin(readerB)) {
    Serial.println("Reader B: NO ANSWER. Check its switch 1 is ON; swap the SDA / SCL wires.");
  }
  if (readerA.found || readerB.found) Serial.println("Hold a card or sticker to a reader...");
}

void loop() {
  if (!readerA.found && !readerB.found) {
    delay(3000);
    Serial.println("No reader found. Fix the wiring, then press RST on the kit.");
    return;
  }
  poll(readerA);
  poll(readerB);
  delay(100);
}
