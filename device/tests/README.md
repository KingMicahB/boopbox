# Bench test sketches

One sketch per step of [the wiring plan](../WIRING.md), each printing `PASS` / `FAIL` style
lines on the Serial Monitor. Every sketch is a single file with no libraries to install.

| Sketch | Wiring plan step | Proves |
|---|---|---|
| `01_i2c_scan` | Phase 0 step 8, Phase 1 step 1 | Which revision of the Audio Kit this is |
| `02_tone` | Step 2 | Codec, amplifier, speaker, and which speaker socket is left / right |
| `03_sd_card` | Step 3 | SD card mounts, lists, writes, and `test.wav` plays |
| `04_nfc` | Steps 5 and 6 | Reader A (serial) and reader B (I2C), one or both |
| `05_encoders` | Step 7 | The MCP23017 expander and both knobs |
| `06_all_together` | Step 9 | Everything at once, for the hour on battery |

## How far these have been checked

They have **not** run on the real hardware, and have not been built with the ESP32 toolchain.
What was done instead:

* every sketch passes a C++ syntax and type check against stand-in Arduino headers;
* the calls into the ESP32 core (`ESP_I2S`, `Wire`, `SD`, `SD_MMC`, `HardwareSerial`) were
  compared with the core's own headers, versions 3.0.7 and current;
* the PN532 code was run against a simulated reader on both the serial and the I2C path, the
  knob code against a simulated MCP23017, and the WAV reader against generated files.

So expect the logic to hold, and treat the first run of each as the real test. The parts most
likely to need a tweak on real boards are timing (reader wake-up, I2C with long jumper wires)
and the codec start-up values in `02_tone`.

## Setting up (once)

1. Install **Arduino IDE 2**.
2. *Tools → Board → Boards Manager*, search `esp32`, install **esp32 by Espressif Systems**,
   version **3.0 or newer** (the sketches use its `ESP_I2S` library, which 2.x does not have).
3. *Tools → Board → esp32 →* **ESP32 Dev Module**.
4. Plug USB into the kit's socket marked **UART** and pick the new port under *Tools → Port*.
   If no port appears, install the CP210x USB driver from Silicon Labs.
5. *Tools → Serial Monitor*, set **115200 baud**.

For each test: open the sketch's `.ino`, press **Upload**, watch the Serial Monitor.

If an upload fails with "Failed to connect":

* hold the kit's **BOOT** button, tap **RST**, release BOOT, and upload again;
* take the SD card out while uploading (its data line shares a pin the ESP32 reads at start-up),
  then push it back in and press **RST**.

## Making `test.wav`

Tests 3 and 6 play a plain WAV rather than an MP3, so no decoder library is needed. From any
song:

```
ffmpeg -i song.mp3 -ac 2 -ar 44100 -c:a pcm_s16le test.wav
```

Copy it to the top folder of the FAT32-formatted card. A minute or two is plenty (about 10 MB
per minute).

## What each one prints when it works

**`01_i2c_scan`**

```
BoopBox test 1: I2C scan
  SDA 33 / SCL 32: something answers at 0x10
PASS: ES8388 at 0x10 on SDA 33 / SCL 32. The wiring plan fits this board.
```

**`02_tone`**: `i2s: ok`, `codec: ok`, then `LEFT`, `RIGHT`, `BOTH` over and over with a beep
for each. One speaker sounds on `LEFT` or `RIGHT`, and on `BOTH`. To change the loudness, edit
`VOLUME` near the top (0 to 33).

**`03_sd_card`**

```
card: mounted over SPI, 59638 MB
  test.wav                         10584044 bytes
  (1 entries)
write test: ok
wav: 44100 Hz, 2 channel(s), 60 seconds. Playing...
wav: finished
```

**`04_nfc`**

```
Reader A: found PN532, firmware 1.6
Reader B: found PN532, firmware 1.6
Hold a card or sticker to a reader...
A: card on  04A1B2C3
A: card off
B: card on  04112233445566
```

The UID is printed in the same form the phone app stores (upper-case hex, no spaces), so a card
set up in the app can later be matched on the box.

**`05_encoders`**

```
Expander found at 0x20
Interrupt wire (IO5) is resting high, as it should
Turn and press the knobs...
Knob 1: +1 (total 1)
Knob 1: -1 (total 0)
Knob 2: pressed
```

One line per click. Spinning a knob very fast can drop a click; it should never count the wrong
way.

**`06_all_together`**: the restart reason, what was found, then `-> playing` / `-> stopped` as
cards come and go, `Volume 19 of 33`, `Jump +5 s`, `-> paused`, and one status line a minute:

```
[up 12 min] playing, volume 22, readers AB, knobs ok, free memory 251432
```

If the minutes ever start again from 0, the box restarted: the line at the top after the restart
says why (`BROWN-OUT` = the battery or 3.3 V supply dipped).
