// BoopBox device test 2: codec, amplifier and speaker.
//
// Speaker plugged into either speaker socket. Serial Monitor at 115200.
//
// PASS: "codec: ok", then a repeating pattern: beep LEFT, beep RIGHT, beep BOTH, pause.
//       The speaker sounds on LEFT or RIGHT (which tells you its socket) and on BOTH.
// FAIL: "codec: NO ANSWER" -> run test 1 again. Silence with "codec: ok" -> speaker plug
//       not seated, or headphones plugged in.

#include <Wire.h>
#include <ESP_I2S.h>

const int PIN_CODEC_SDA = 33, PIN_CODEC_SCL = 32;
const int PIN_I2S_BCK = 27, PIN_I2S_WS = 25, PIN_I2S_DOUT = 26, PIN_I2S_MCLK = 0;
const int PIN_AMP_ENABLE = 21;
const uint8_t ES8388_ADDR = 0x10;

const uint32_t SAMPLE_RATE = 44100;
const uint8_t VOLUME = 18;  // 0 quietest .. 33 loudest. Start low: the amplifier is 3 W.

I2SClass i2s;

bool codecWrite(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(ES8388_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

// Output level of all four codec outputs: 0 = -45 dB, 30 = 0 dB, 33 = +4.5 dB.
bool codecVolume(uint8_t v) {
  if (v > 33) v = 33;
  bool ok = true;
  for (uint8_t reg = 0x2E; reg <= 0x31; reg++) ok &= codecWrite(reg, v);
  return ok;
}

// Playback only: DAC to all four outputs, 16-bit I2S, the ESP32 supplies every clock.
bool codecBegin(uint8_t volume) {
  bool ok = true;
  ok &= codecWrite(0x19, 0x04);  // DAC muted while setting up
  ok &= codecWrite(0x01, 0x50);  // power management defaults
  ok &= codecWrite(0x02, 0x00);  // all blocks powered
  ok &= codecWrite(0x08, 0x00);  // codec is the I2S slave
  ok &= codecWrite(0x04, 0xC0);  // DAC and outputs off for now
  ok &= codecWrite(0x00, 0x12);  // reference on, playback and record share clocks
  ok &= codecWrite(0x17, 0x18);  // 16-bit samples, I2S format
  ok &= codecWrite(0x18, 0x02);  // master clock = 256 x sample rate
  ok &= codecWrite(0x26, 0x00);  // mixer inputs
  ok &= codecWrite(0x27, 0x90);  // left DAC to left mixer
  ok &= codecWrite(0x2A, 0x90);  // right DAC to right mixer
  ok &= codecWrite(0x2B, 0x80);  // one word clock for DAC and ADC
  ok &= codecWrite(0x2D, 0x00);  // output resistance
  ok &= codecWrite(0x1A, 0x00);  // DAC digital volume left, 0 dB
  ok &= codecWrite(0x1B, 0x00);  // DAC digital volume right, 0 dB
  ok &= codecWrite(0x03, 0xFF);  // microphones / ADC off
  ok &= codecVolume(volume);
  ok &= codecWrite(0x04, 0x3C);  // DAC on, LOUT1 ROUT1 LOUT2 ROUT2 on
  ok &= codecWrite(0x02, 0xF0);  // restart the codec's clock state machine...
  ok &= codecWrite(0x02, 0x00);  // ...with the new settings
  ok &= codecWrite(0x19, 0x00);  // unmute
  return ok;
}

void beep(float hz, bool left, bool right, uint32_t ms) {
  static int16_t buf[2 * 256];
  float phase = 0, step = 2 * PI * hz / SAMPLE_RATE;
  uint32_t frames = SAMPLE_RATE * ms / 1000;
  while (frames > 0) {
    uint32_t n = frames < 256 ? frames : 256;
    for (uint32_t i = 0; i < n; i++) {
      int16_t s = (int16_t)(8000 * sinf(phase));
      phase += step;
      if (phase > 2 * PI) phase -= 2 * PI;
      buf[2 * i] = left ? s : 0;
      buf[2 * i + 1] = right ? s : 0;
    }
    i2s.write((uint8_t *)buf, n * 4);
    frames -= n;
  }
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("BoopBox test 2: tone");

  pinMode(PIN_AMP_ENABLE, OUTPUT);
  digitalWrite(PIN_AMP_ENABLE, HIGH);

  // Clocks first: the codec wants its master clock running while it is set up.
  i2s.setPins(PIN_I2S_BCK, PIN_I2S_WS, PIN_I2S_DOUT, -1, PIN_I2S_MCLK);
  bool i2sOk = i2s.begin(I2S_MODE_STD, SAMPLE_RATE, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO);
  Serial.printf("i2s:   %s\n", i2sOk ? "ok" : "FAILED TO START");

  Wire.begin(PIN_CODEC_SDA, PIN_CODEC_SCL, 100000);
  bool codecOk = codecBegin(VOLUME);
  Serial.printf("codec: %s\n", codecOk ? "ok" : "NO ANSWER (run test 1 again)");
}

void loop() {
  Serial.println("LEFT");
  beep(440, true, false, 600);
  delay(300);
  Serial.println("RIGHT");
  beep(660, false, true, 600);
  delay(300);
  Serial.println("BOTH");
  beep(880, true, true, 600);
  delay(1500);
}
