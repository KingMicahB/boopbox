// BoopBox device test 6: everything together, for the one-hour run on battery.
//
// Wiring as in tests 4 and 5, speaker and SD card in. Serial Monitor at 115200 (optional:
// the test also works with no computer attached).
//
//   Card in the slot (reader A) or sticker on the spot (reader B)  -> sound starts
//   Both taken away                                                -> sound stops
//   Knob 1 turn  -> volume          Knob 1 press -> pause / carry on
//   Knob 2 turn  -> 5 seconds back / forward in test.wav (or the pitch, if there is no file)
//
// Plays /test.wav from the SD card in a loop; without a card or file it plays a tone instead.
// Prints the reason for the last restart at the top and a status line every minute: an
// uptime that starts again from zero, or "BROWN-OUT", means the supply dipped.

#include <FS.h>
#include <SD.h>
#include <SD_MMC.h>
#include <SPI.h>
#include <Wire.h>
#include <ESP_I2S.h>
#include <esp_system.h>

// Reader A (serial), reader B and the expander (I2C), the expander's interrupt wire
const int PIN_A_RX = 19, PIN_A_TX = 22;
const int PIN_B_SDA = 18, PIN_B_SCL = 23;
const int PIN_INT = 5;
const uint8_t PN532_I2C_ADDR = 0x24;
const bool ALTERNATE_FIELDS = true;

// On the kit already
const int PIN_SD_CLK = 14, PIN_SD_MISO = 2, PIN_SD_MOSI = 15, PIN_SD_CS = 13;
const int PIN_CODEC_SDA = 33, PIN_CODEC_SCL = 32;
const int PIN_I2S_BCK = 27, PIN_I2S_WS = 25, PIN_I2S_DOUT = 26, PIN_I2S_MCLK = 0;
const int PIN_AMP_ENABLE = 21;
const uint8_t ES8388_ADDR = 0x10;

const uint8_t REG_IODIRA = 0x00, REG_IODIRB = 0x01, REG_GPINTENA = 0x04, REG_INTCONA = 0x08;
const uint8_t REG_IOCON = 0x0A, REG_GPPUA = 0x0C, REG_GPPUB = 0x0D, REG_INTCAPA = 0x10, REG_GPIOA = 0x12;

const int SEEK_SECONDS = 5;

struct Wav {
  File file;
  uint32_t rate = 0;
  uint16_t channels = 0;
  uint32_t dataStart = 0, dataLen = 0, left = 0;
};

struct Reader {
  char name;
  bool i2c;
  bool found;
  uint8_t uid[10];
  uint8_t uidLen;
  uint8_t misses;
};

struct Knob {
  uint8_t clkBit, dtBit, swBit;
  uint8_t state;
  int8_t travel;
  bool down;
  uint32_t downChangedMs;
  int32_t total;
};

Reader readerA = {'A', false, false, {0}, 0, 0};
Reader readerB = {'B', true, false, {0}, 0, 0};
Knob knobs[2] = {
  {0, 1, 2, 3, 0, false, 0, 0},
  {3, 4, 5, 3, 0, false, 0, 0},
};

HardwareSerial &NfcSerial = Serial1;
TwoWire &NfcWire = Wire1;  // reader B and the expander share this I2C port
TwoWire &Bus = Wire1;
I2SClass i2s;
fs::FS *card = nullptr;
uint8_t expander = 0;
bool interruptSeen = false;

Wav wav;
bool haveWav = false;
uint32_t sampleRate = 44100;
int volume = 18;  // 0 quietest .. 33 loudest

// Set by the main loop, read by the sound task
volatile bool playing = false, paused = false;
volatile uint32_t restartCount = 0;
// Counted up by the knob task, read by the others
volatile int32_t volumeTurns = 0, seekTurns = 0;
volatile uint32_t pauseClicks = 0;

// ---------------------------------------------------------------- codec (same as test 2)

bool codecWrite(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(ES8388_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

bool codecVolume(uint8_t v) {
  if (v > 33) v = 33;
  bool ok = true;
  for (uint8_t reg = 0x2E; reg <= 0x31; reg++) ok &= codecWrite(reg, v);
  return ok;
}

bool codecBegin(uint8_t volume) {
  static const uint8_t setup[][2] = {
    {0x19, 0x04}, {0x01, 0x50}, {0x02, 0x00}, {0x08, 0x00}, {0x04, 0xC0}, {0x00, 0x12},
    {0x17, 0x18}, {0x18, 0x02}, {0x26, 0x00}, {0x27, 0x90}, {0x2A, 0x90}, {0x2B, 0x80},
    {0x2D, 0x00}, {0x1A, 0x00}, {0x1B, 0x00}, {0x03, 0xFF},
  };
  bool ok = true;
  for (auto &rv : setup) ok &= codecWrite(rv[0], rv[1]);
  ok &= codecVolume(volume);
  ok &= codecWrite(0x04, 0x3C);
  ok &= codecWrite(0x02, 0xF0);
  ok &= codecWrite(0x02, 0x00);
  ok &= codecWrite(0x19, 0x00);
  return ok;
}

// ---------------------------------------------------------------- WAV file (same as test 3)

uint32_t le32(const uint8_t *b) {
  return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}

// Opens a 16-bit PCM WAV and leaves the file at the first sample. Says why if it can't.
bool wavOpen(fs::FS &fs, const char *path, Wav &w) {
  w.file = fs.open(path);
  if (!w.file) {
    Serial.printf("wav: %s is not on the card\n", path);
    return false;
  }
  uint8_t b[16];
  if (w.file.read(b, 12) != 12 || memcmp(b, "RIFF", 4) != 0 || memcmp(b + 8, "WAVE", 4) != 0) {
    Serial.println("wav: not a WAV file");
    return false;
  }
  bool haveFormat = false;
  while (w.file.read(b, 8) == 8) {
    uint32_t size = le32(b + 4);
    if (memcmp(b, "fmt ", 4) == 0) {
      if (size < 16 || w.file.read(b, 16) != 16) return false;
      uint16_t format = b[0] | (b[1] << 8);
      uint16_t bits = b[14] | (b[15] << 8);
      w.channels = b[2] | (b[3] << 8);
      w.rate = le32(b + 4);
      if (format != 1 || bits != 16 || w.channels < 1 || w.channels > 2) {
        Serial.printf("wav: need 16-bit PCM, mono or stereo (this is format %u, %u bits, %u channels)\n",
                      format, bits, w.channels);
        return false;
      }
      w.file.seek(w.file.position() + (size - 16) + (size & 1));
      haveFormat = true;
    } else if (memcmp(b, "data", 4) == 0) {
      if (!haveFormat) return false;
      w.dataStart = w.file.position();
      uint32_t rest = w.file.size() - w.dataStart;
      w.dataLen = size < rest ? size : rest;
      w.left = w.dataLen;
      return true;
    } else {
      w.file.seek(w.file.position() + size + (size & 1));
    }
  }
  Serial.println("wav: no sound data found in the file");
  return false;
}

// Up to `frames` stereo frames into `out` (mono files are doubled up). Returns frames read.
size_t wavRead(Wav &w, int16_t *out, size_t frames) {
  size_t frameBytes = 2 * w.channels;
  size_t want = frames * frameBytes;
  if (want > w.left) want = w.left - (w.left % frameBytes);
  if (want == 0) return 0;
  int got = w.file.read((uint8_t *)out, want);
  if (got <= 0) return 0;
  w.left -= got;
  size_t n = got / frameBytes;
  if (w.channels == 1) {
    for (size_t i = n; i-- > 0;) out[2 * i] = out[2 * i + 1] = out[i];
  }
  return n;
}

// Moves to `byteOffset` into the sound data (rounded to a whole frame).
void wavSeekTo(Wav &w, uint32_t byteOffset) {
  uint32_t frameBytes = 2 * w.channels;
  if (byteOffset > w.dataLen) byteOffset = w.dataLen;
  byteOffset -= byteOffset % frameBytes;
  w.file.seek(w.dataStart + byteOffset);
  w.left = w.dataLen - byteOffset;
}

void wavSkip(Wav &w, int32_t seconds) {
  int64_t pos = (int64_t)(w.dataLen - w.left) + (int64_t)seconds * w.rate * 2 * w.channels;
  if (pos < 0) pos = 0;
  if (pos > w.dataLen) pos = w.dataLen;
  wavSeekTo(w, (uint32_t)pos);
}

// ---------------------------------------------------------------- SD card (same as test 3)

bool mountCard() {
  // The way most Audio Kit software talks to the card: SPI.
  SPI.begin(PIN_SD_CLK, PIN_SD_MISO, PIN_SD_MOSI, PIN_SD_CS);
  if (SD.begin(PIN_SD_CS, SPI, 10000000)) {
    card = &SD;
    Serial.printf("card: mounted over SPI, %llu MB\n", SD.cardSize() / (1024ULL * 1024ULL));
    return true;
  }
  SD.end();
  SPI.end();
  // Fallback: the ESP32's SD controller on one data line (CLK 14, CMD 15, D0 2).
  if (SD_MMC.begin("/sdcard", true)) {
    card = &SD_MMC;
    Serial.printf("card: mounted over SD_MMC (1 data line), %llu MB\n", SD_MMC.cardSize() / (1024ULL * 1024ULL));
    return true;
  }
  return false;
}

// ---------------------------------------------------------------- NFC readers (same as test 4)

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

// ---------------------------------------------------------------- knobs (same as test 5)

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
  if (knob == 0) volumeTurns = volumeTurns + direction;
  else seekTurns = seekTurns + direction;
}

void onPress(int knob) {
  if (knob == 0) pauseClicks = pauseClicks + 1;
  else Serial.println("Knob 2: pressed (nothing assigned)");
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

// ---------------------------------------------------------------- tasks

// Reads the knobs a thousand times a second, whatever the main loop is busy with.
void knobTask(void *) {
  for (;;) {
    serviceKnobs();
    vTaskDelay(1);
  }
}

// Feeds the codec. The only code that touches the WAV file once setup() is done.
void soundTask(void *) {
  static int16_t buf[2 * 256];
  float phase = 0, toneHz = 440;
  uint32_t restartSeen = 0;
  int32_t seekSeen = 0;
  for (;;) {
    uint32_t restartNow = restartCount;
    if (restartNow != restartSeen) {
      restartSeen = restartNow;
      if (haveWav) wavSeekTo(wav, 0);
    }
    int32_t seekNow = seekTurns;
    if (seekNow != seekSeen) {
      int32_t clicks = seekNow - seekSeen;
      seekSeen = seekNow;
      if (haveWav) wavSkip(wav, clicks * SEEK_SECONDS);
      else toneHz = constrain(toneHz * powf(2.0f, clicks / 12.0f), 110.0f, 1760.0f);
    }
    if (!playing || paused) {
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }
    size_t n = 256;
    if (haveWav) {
      n = wavRead(wav, buf, 256);
      if (n == 0) {  // end of the file: go round again
        wavSeekTo(wav, 0);
        continue;
      }
    } else {
      float step = 2 * PI * toneHz / sampleRate;
      for (size_t i = 0; i < n; i++) {
        buf[2 * i] = buf[2 * i + 1] = (int16_t)(8000 * sinf(phase));
        phase += step;
        if (phase > 2 * PI) phase -= 2 * PI;
      }
    }
    i2s.write((uint8_t *)buf, n * 4);
  }
}

// ---------------------------------------------------------------- the test

const char *restartReason() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON: return "power switched on";
    case ESP_RST_SW: return "restart asked for by software (also after an upload)";
    case ESP_RST_BROWNOUT: return "BROWN-OUT: the supply voltage dipped";
    case ESP_RST_PANIC: return "CRASH in the sketch";
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT: return "WATCHDOG: the sketch hung";
    case ESP_RST_DEEPSLEEP: return "woke from deep sleep";
    default: return "reset button or other";
  }
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("BoopBox test 6: all together");
  Serial.printf("Last restart: %s\n", restartReason());

  pinMode(PIN_AMP_ENABLE, OUTPUT);
  digitalWrite(PIN_AMP_ENABLE, HIGH);
  pinMode(PIN_INT, INPUT_PULLUP);

  // Sound source: /test.wav if there is one, otherwise a tone.
  if (mountCard()) {
    haveWav = wavOpen(*card, "/test.wav", wav);
    if (haveWav) sampleRate = wav.rate;
  } else {
    Serial.println("card: none found");
  }
  Serial.printf("sound: %s\n", haveWav ? "/test.wav from the SD card, looped" : "a tone (no test.wav)");

  i2s.setPins(PIN_I2S_BCK, PIN_I2S_WS, PIN_I2S_DOUT, -1, PIN_I2S_MCLK);
  if (!i2s.begin(I2S_MODE_STD, sampleRate, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO)) {
    Serial.println("i2s: FAILED TO START");
  }
  Wire.begin(PIN_CODEC_SDA, PIN_CODEC_SCL, 100000);
  if (!codecBegin(volume)) Serial.println("codec: NO ANSWER (run tests 1 and 2 again)");

  NfcSerial.begin(115200, SERIAL_8N1, PIN_A_RX, PIN_A_TX);
  Wire1.begin(PIN_B_SDA, PIN_B_SCL, 100000);
  Wire1.setTimeOut(50);
  if (!readerBegin(readerA)) Serial.println("Reader A: NO ANSWER (run test 4)");
  if (!readerBegin(readerB)) Serial.println("Reader B: NO ANSWER (run test 4)");

  if (findExpander() && expanderBegin()) {
    Serial.printf("Expander found at 0x%02X\n", expander);
    xTaskCreatePinnedToCore(knobTask, "knobs", 4096, nullptr, 3, nullptr, 1);
  } else {
    expander = 0;
    Serial.println("Expander: NOT FOUND (run test 5). Carrying on without knobs.");
  }
  xTaskCreatePinnedToCore(soundTask, "sound", 8192, nullptr, 5, nullptr, 1);
  Serial.println("Put a card in the slot or a sticker on the spot...");
}

void loop() {
  static bool hadCard = false;
  static int32_t volumeSeen = 0, seekSeen = 0;
  static uint32_t pauseSeen = 0, lastStatusMs = 0;

  poll(readerA);
  poll(readerB);
  bool haveCard = readerA.uidLen != 0 || readerB.uidLen != 0;
  if (haveCard != hadCard) {
    hadCard = haveCard;
    if (haveCard) {
      restartCount = restartCount + 1;  // start the sound from the beginning
      paused = false;
    }
    playing = haveCard;
    Serial.println(haveCard ? "-> playing" : "-> stopped");
  }

  int32_t volumeNow = volumeTurns;
  if (volumeNow != volumeSeen) {
    volume = constrain(volume + (int)(volumeNow - volumeSeen), 0, 33);
    volumeSeen = volumeNow;
    codecVolume(volume);
    Serial.printf("Volume %d of 33\n", volume);
  }

  uint32_t pauseNow = pauseClicks;
  if (pauseNow != pauseSeen) {
    pauseSeen = pauseNow;
    if (playing) {
      paused = !paused;
      Serial.println(paused ? "-> paused" : "-> carrying on");
    }
  }

  int32_t seekNow = seekTurns;
  if (seekNow != seekSeen) {
    Serial.printf("Jump %+d s\n", (int)(seekNow - seekSeen) * SEEK_SECONDS);
    seekSeen = seekNow;
  }

  if (millis() - lastStatusMs >= 60000) {
    lastStatusMs = millis();
    Serial.printf("[up %lu min] %s, volume %d, readers %c%c, knobs %s, free memory %u\n",
                  (unsigned long)(millis() / 60000), playing ? (paused ? "paused" : "playing") : "stopped",
                  volume, readerA.found ? 'A' : '-', readerB.found ? 'B' : '-',
                  expander == 0 ? "missing" : (interruptSeen ? "ok" : "ok (interrupt wire not seen yet)"),
                  (unsigned)ESP.getFreeHeap());
  }
  delay(50);
}
