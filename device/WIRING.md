# BoopBox device: wiring plan

A stand-alone BoopBox: put a card on the box and it plays from the SD card, the knob is volume
and play / pause. Built from an ESP32-Audio-Kit (ES8388), a PN532 NFC reader, a KY-040 rotary
encoder, one speaker and a LiPo cell.

Only five signal wires are needed. The codec, amplifier, SD card slot, charger and headphone
jack are already wired on the Audio Kit.

> Nothing here has been tested on the actual boards yet. The Audio Kit pin numbers come from
> community board definitions (sources at the end) and the kit ships in revisions that differ.
> Phase 0 is there to confirm your revision before any wire goes on.

## What each part is for

| Part | Role | Used |
|---|---|---|
| ESP32-Audio-Kit | Brain, codec, 2 × 3 W amplifier, SD slot, LiPo charger | 1 |
| PN532 V3 | Card reader, in HSU (serial) mode | 1, the other is a spare |
| KY-040 encoder | Volume (turn), play / pause (press) | 1, four spares |
| 4 Ω 3 W speaker | Plugs into one speaker output | 1 |
| 3.7 V 3000 mAh LiPo | Plugs into the BAT socket, charged by the kit over USB | 1 |
| 64 GB microSD | Audio files. Reformat as **FAT32** first (it ships as exFAT, which the ESP32 SD libraries don't read by default; Windows needs a tool such as Rufus or guiformat for FAT32 above 32 GB) | 1 |
| Mini breadboard | 3V3 / GND hub for the bench test | 1, the other is a spare |
| Lever connectors | 3V3 / GND hubs in the final build | 2, one spare |
| JST PH2.0 / XH2.54 leads | Speaker and battery plugs. Only one size fits the kit's sockets, see Phase 0 | 2 |

### Not in the order, worth getting

* **Multimeter** (any cheap one). The battery step needs a polarity check, and a reversed LiPo
  destroys the board in an instant. Without one, the only check is the + / − print on the board
  against the red wire, which is not enough for pre-made plugs that are often wired backwards.
* **Slide or rocker switch** (rated 1 A or more). The kit has no power switch: with the battery
  plugged in, it is on. For the first tests, unplugging the battery is the switch.

### About the cards

The CUID cards, the white card and the blue fob are Mifare Classic 1K. The box and the phone app
both identify them by UID, so the same card can play on both. The app's **Write to card** and
"tap opens BoopBox" need NDEF tags (NTAG213 / 215 stickers or cards); on Mifare Classic they only
work on phones with an NXP NFC chip.

## Pin map

| Signal | Audio Kit header pin | Goes to | Notes |
|---|---|---|---|
| 3.3 V | `3V3` | PN532 `VCC`, encoder `+` | **Never 5 V.** Both modules pull their signal lines up to their supply pin |
| Ground | `GND` | PN532 `GND`, encoder `GND` | |
| Serial RX (ESP32 listens) | `IO19` | PN532 `TXD` (the pad printed `SDA` on the front) | Shared with KEY3 on the kit |
| Serial TX (ESP32 talks) | `IO22` | PN532 `RXD` (the pad printed `SCL` on the front) | Shared with LED D4, which flickers while the reader is polled |
| Encoder A | `IO18` | encoder `CLK` | Shared with KEY5 |
| Encoder B | `IO23` | encoder `DT` | Shared with KEY4 |
| Encoder button | `IO5` | encoder `SW` | Shared with KEY6. Firmware turns on the internal pull-up |

Leave these header pins empty:

| Pin | Why |
|---|---|
| `IO21` | Amplifier enable. Anything hanging on it can mute the speaker |
| `IO0` | Codec master clock and the boot-mode pin |
| `TX0`, `RX0` | The USB serial port: flashing and log output |
| `RST` | Reset |

Already wired on the kit, nothing to connect:

| Function | GPIO |
|---|---|
| Codec control (I2C) | SDA 33, SCL 32 |
| Codec audio (I2S) | MCLK 0, BCK 27, WS 25, DOUT 26, DIN 35 |
| SD card | CLK 14, CMD / MOSI 15, D0 / MISO 2, D3 / CS 13 (D1 4, D2 12 in 4-bit mode) |
| Headphone detect | 39 |
| KEY1 (stays usable as a spare button) | 36 |

PN532 pins `SCK`, `MISO`, `MOSI`, `SS`, `IRQ`, `RSTO` stay unconnected.

```
   PN532 V3 (HSU)             ESP32-Audio-Kit header            KY-040 encoder
                              +---------------------+
   VCC ------- 3V3 hub -------| 3V3                 |------ 3V3 hub ------- +
   GND ------- GND hub -------| GND                 |------ GND hub ------- GND
   TXD (SDA) ---------------->| IO19                |
   RXD (SCL) <----------------| IO22           IO18 |<--------------------- CLK
                              |                IO23 |<--------------------- DT
                              |                 IO5 |<--------------------- SW
                              | IO21 IO0 TX0 RX0 RST: empty
                              +---------------------+
   Speaker ==== JST ==== SPK socket (either one)
   LiPo ==== switch (final build) ==== JST ==== BAT socket
```

## Phase 0: checks before any wire goes on

Power the kit from USB only. The battery stays in its bag until Phase 1 step 6.

1. **Read the header silkscreen.** Find the pins printed `3V3`, `GND`, `IO5`, `IO18`, `IO19`,
   `IO22`, `IO23`. Go by the printed labels, not by position.
2. **Find the revision.** Most ES8388 kits have the codec on GPIO 33 / 32, which is what this
   plan assumes. An older run has it on 18 / 23 with the audio clock on 5: on that one the
   encoder pins above are taken and this pin map must change. The check is an I2C scan sketch
   that looks for address `0x10` on 33 / 32, then on 18 / 23. **Stop here if it answers on
   18 / 23.**
3. **Set the kit's DIP switches** to `1 OFF, 2 ON, 3 ON, 4 OFF, 5 OFF`. Switches 2 and 3 hand
   GPIO 15 and 13 to the SD card, 1 would give GPIO 13 to KEY2, 4 and 5 are for a JTAG debugger.
4. **Set the PN532's two small switches** both to `OFF` (HSU / serial mode; the print next to
   them reads `0 0`).
5. **Test-fit the JST plugs, unpowered.** Try a PH2.0 and an XH2.54 plug in the `BAT` socket and
   in a speaker socket. The right one slides in and clicks with no force (PH has 2.0 mm between
   pins, XH 2.5 mm). I expect PH2.0 for all three sockets, but sellers list both.
6. **First soldering job: the PN532's 4-pin header** (`GND VCC SDA SCL`). Push the pins through
   so they stick out on the **chip side**. The other side stays flat and later sits against the
   lid. Iron at 330 °C, a touch of flux, heat pad and pin together for two seconds, feed solder,
   remove solder, then iron. Each joint should be a small shiny cone. The second PN532 is there
   if this one goes wrong.
7. **Flip the PN532 over** and read the back: the same two pads are printed `TXD` and `RXD`
   there. Those are the names the pin map uses.

## Phase 1: bench test

One thing at a time, each proved before the next goes on. Unplug USB before adding or moving
any wire. Every checkpoint needs a small test sketch flashed through the USB socket marked
`UART`.

**Step 1. Kit alone.** USB into `UART`, flash the I2C scan from Phase 0.
*Checkpoint:* the codec answers at `0x10` on 33 / 32.

**Step 2. Speaker.** If the speaker has bare tabs, solder a JST lead of the fitting size to them
(be quick, the tabs sit in plastic), then plug it into either speaker socket. Polarity does not
matter with one speaker.
*Checkpoint:* a test tone plays.

**Step 3. SD card.** FAT32-formatted card with one MP3 on it, pushed in until it clicks.
*Checkpoint:* the file list prints and the MP3 plays through the speaker.

**Step 4. Power hub and PN532.** The mini breadboard's holes are joined in strips of five. Pick
one strip as the 3V3 hub and one on the other half as the GND hub.

| # | From | To | Jumper |
|---|---|---|---|
| 1 | Kit `3V3` | 3V3 hub | female to male |
| 2 | Kit `GND` | GND hub | female to male |
| 3 | 3V3 hub | PN532 `VCC` | male to female |
| 4 | GND hub | PN532 `GND` | male to female |
| 5 | Kit `IO19` | PN532 `TXD` (`SDA`) | female to female |
| 6 | Kit `IO22` | PN532 `RXD` (`SCL`) | female to female |

Use one colour for 3V3 and one for GND throughout, and check wires 1 to 4 twice before plugging
USB back in.
*Checkpoint:* the sketch prints the PN532 firmware version, then a UID each time a card or the
fob is held to the antenna. Note the reading distance; it sets how thick the lid may be.
*If the reader does not answer:* swap wires 5 and 6 (crossed TX / RX is the usual cause), then
check both PN532 switches are `OFF`.

**Step 5. Encoder.**

| # | From | To | Jumper |
|---|---|---|---|
| 7 | 3V3 hub | encoder `+` | male to female |
| 8 | GND hub | encoder `GND` | male to female |
| 9 | Kit `IO18` | encoder `CLK` | female to female |
| 10 | Kit `IO23` | encoder `DT` | female to female |
| 11 | Kit `IO5` | encoder `SW` | female to female |

*Checkpoint:* the count goes up one per click clockwise, down anticlockwise, and the press is
reported. If the direction is backwards, swap wires 9 and 10. The reader from step 4 still works.

**Step 6. Battery, last.** USB unplugged.

1. Check the cell has a protection board (a small strip under the tape where the wires leave).
   A bare cell with none should not go in a child's toy.
2. Find `+` and `−` printed next to the `BAT` socket.
3. With the multimeter on DC volts, measure the plug you are about to push in: about 3.7 to
   4.2 V, and the pin that will sit on the board's `+` must be the positive one. Do this even if
   the battery came with a plug that fits, as ready-made plugs are often the other way round.
4. If the battery has bare wires or the wrong plug, join it to the fitting JST lead **one wire at
   a time**: cut, slide heat shrink on, solder, shrink, and only then touch the second wire. The
   two battery wires must never be bare at the same moment.
5. Plug the battery in.

*Checkpoint:* the kit runs with USB unplugged, tone and reader still work at full volume. Plug
USB back in and the charge LED lights. Expect a full charge of 3000 mAh through the kit's small
charger to take many hours.

**Step 7. All together.** Run the real firmware (or a combined test) for an hour on battery:
card on plays, knob changes volume, card off stops.

## Phase 2: final build

The connections are the same eleven wires. What changes is how they are held.

1. **Plan the positions first.**
   * PN532 flat side against the inside of the lid, where the card will rest. Keep the lid at
     most 3 mm of plastic or wood, with no metal, foil or metallic paint over the antenna.
   * At least 3 cm between the PN532 and the speaker magnet, the battery and the Audio Kit. Metal
     close to the antenna shortens the reading distance. Test with the parts taped in place
     before drilling anything.
   * Audio Kit against a wall with both USB sockets reachable (charging and updates) and the SD
     card removable.
   * Battery where nothing can press into it: no screw tips, no standoff ends, no solder joints
     facing it. Hold it with double-sided foam tape, never a clamp.
2. **Mount** the Audio Kit and the PN532 on M3 standoffs through their corner holes. Nylon
   washers or the nylon standoffs go next to the PN532 antenna, not steel. The encoder goes
   through the wall by its shaft, or on standoffs through its two holes.
3. **Replace the breadboard with two lever connectors**, one for 3V3 and one for GND. Cut the
   breadboard end off wires 1 to 4, 7 and 8, strip 10 mm, twist the strands, fold the bare end
   back on itself so the thin wire is doubled, and clamp three wires into each connector. Tug
   each wire. Mark the two connectors so they can't be mixed up.
4. **Keep the plugs on the Audio Kit header** rather than soldering to it: the board stays
   removable. Signal wires 5, 6, 9, 10, 11 stay as plain female to female jumpers. Where 10 cm is
   too short, join two jumpers with a soldered splice under heat shrink, not with a male end
   pushed into a female one.
5. **Power switch** (once it arrives) in the battery's **red** wire: battery red to one switch
   lug, the next lug to the red wire of the JST lead, heat shrink over both lugs. Black goes
   straight through. Same one-wire-at-a-time rule as before. The switch must be on for the
   battery to charge.
6. **Tie it down.** Group the jumpers on the header with a small cable tie or a wrap of tape so a
   drop can't work one loose. Leave a little slack in every wire, and keep the speaker lead away
   from the PN532.
7. **Close-up test** before the last screw: full volume on battery with a card on the lid for ten
   minutes, then shake the box. Any reset or crackle means a loose plug or lever connector.

## Known limits of this hardware

* **No battery level.** None of the published pin lists show the battery voltage reaching the
  ESP32, and no header pin can measure a voltage, so firmware cannot warn about a low battery
  without an extra part.
* **No true off without the switch.** Firmware sleep still leaves the codec, USB chip and
  regulator drawing current, so a box left "asleep" slowly empties the cell.
* **KEY3 to KEY6 on the kit** share pins with the reader and encoder. Pressing them does no harm
  but they are no longer free buttons. KEY1 is.
* **Volume on battery** is likely lower than on USB, since the cell gives 3.7 V where USB gives
  5 V. If the box resets at full volume on battery, cap the volume in firmware.

## Sources

* Audio Kit pins per revision, DIP switches, SD pins:
  [arduino-audiokit wiki, GPIO overview](https://github.com/pschatzmann/arduino-audiokit/wiki/GPIO-Overview-by-Selected-Board),
  [SD drive and conflicting buttons](https://github.com/pschatzmann/arduino-audiokit/wiki/Problems-with-the-SD-Drive-or-some-conflicting-Buttons)
* Keys, LED, amplifier enable, headphone detect:
  [esp32-a1s-sendspin](https://github.com/el-bakkali/esp32-a1s-sendspin),
  [squeezelite-esp32 issue 123](https://github.com/sle118/squeezelite-esp32/issues/123)
* PN532 V3 mode switches, supply range and shared I2C / HSU pads: the Elechouse PN532 V3 manual,
  as quoted by resellers ([Elecrow](https://elecrow.com/pn532-nfc-module-pn532-rfid-v3-module.html))
