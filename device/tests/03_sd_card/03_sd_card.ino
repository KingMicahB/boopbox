// BoopBox device test 3: SD card.
//
// Kit DIP switches: 1 OFF, 2 ON, 3 ON, 4 OFF, 5 OFF. Card formatted FAT32 with a file called
// test.wav in the top folder (16-bit PCM; see README.md for the ffmpeg line).
// Serial Monitor at 115200.
//
// PASS: "card: mounted", the file list, "write test: ok", then test.wav plays once.
// FAIL: "card: NOT FOUND" -> card not clicked in, DIP switches, or still exFAT.

#include <FS.h>
#include <SD.h>
#include <SD_MMC.h>
#include <SPI.h>
#include <Wire.h>
#include <ESP_I2S.h>

const int PIN_SD_CLK = 14, PIN_SD_MISO = 2, PIN_SD_MOSI = 15, PIN_SD_CS = 13;
const int PIN_CODEC_SDA = 33, PIN_CODEC_SCL = 32;
const int PIN_I2S_BCK = 27, PIN_I2S_WS = 25, PIN_I2S_DOUT = 26, PIN_I2S_MCLK = 0;
const int PIN_AMP_ENABLE = 21;
const uint8_t ES8388_ADDR = 0x10;
const uint8_t VOLUME = 18;  // 0 quietest .. 33 loudest

struct Wav {
  File file;
  uint32_t rate = 0;
  uint16_t channels = 0;
  uint32_t dataStart = 0, dataLen = 0, left = 0;
};

I2SClass i2s;
fs::FS *card = nullptr;

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

// ---------------------------------------------------------------- WAV file

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

// ---------------------------------------------------------------- card

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

void listTopFolder() {
  File root = card->open("/");
  int count = 0;
  for (File f = root.openNextFile(); f; f = root.openNextFile()) {
    if (f.isDirectory()) Serial.printf("  [folder] %s\n", f.name());
    else Serial.printf("  %-32s %u bytes\n", f.name(), (unsigned)f.size());
    count++;
  }
  Serial.printf("  (%d entries)\n", count);
}

bool writeTest() {
  const char *path = "/boopbox_test.txt";
  File f = card->open(path, FILE_WRITE);
  if (!f) return false;
  f.print("boop");
  f.close();
  f = card->open(path);
  String back = f ? f.readString() : String();
  if (f) f.close();
  card->remove(path);
  return back == "boop";
}

void playWav(const char *path) {
  Wav w;
  if (!wavOpen(*card, path, w)) return;
  Serial.printf("wav: %u Hz, %u channel(s), %u seconds. Playing...\n",
                (unsigned)w.rate, w.channels, (unsigned)(w.dataLen / (2 * w.channels) / w.rate));

  i2s.setPins(PIN_I2S_BCK, PIN_I2S_WS, PIN_I2S_DOUT, -1, PIN_I2S_MCLK);
  if (!i2s.begin(I2S_MODE_STD, w.rate, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO)) {
    Serial.println("i2s: FAILED TO START");
    return;
  }
  Wire.begin(PIN_CODEC_SDA, PIN_CODEC_SCL, 100000);
  if (!codecBegin(VOLUME)) Serial.println("codec: NO ANSWER (run tests 1 and 2 again)");

  static int16_t buf[2 * 256];
  size_t n;
  while ((n = wavRead(w, buf, 256)) > 0) i2s.write((uint8_t *)buf, n * 4);
  w.file.close();
  Serial.println("wav: finished");
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("BoopBox test 3: SD card");

  pinMode(PIN_AMP_ENABLE, OUTPUT);
  digitalWrite(PIN_AMP_ENABLE, HIGH);

  if (!mountCard()) {
    Serial.println("card: NOT FOUND. Check it clicked in, the DIP switches (OFF ON ON OFF OFF),");
    Serial.println("      and that it is formatted FAT32, not exFAT.");
    return;
  }
  listTopFolder();
  Serial.printf("write test: %s\n", writeTest() ? "ok" : "FAILED (card locked or faulty?)");
  playWav("/test.wav");
  Serial.println("Done. Press RST on the kit to run again.");
}

void loop() {
  delay(1000);
}
