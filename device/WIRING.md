# BoopBox device: wiring plan

A stand-alone BoopBox: slide a card into the slot or boop a sticker on the lid and it plays from
the SD card. One knob is volume, the other moves back and forward in the song. Built from an
ESP32-Audio-Kit (ES8388), two PN532 NFC readers, two KY-040 rotary encoders, one speaker and a
LiPo cell.

The codec, amplifier, SD card slot, charger and headphone jack are already wired on the Audio
Kit. Everything added here hangs off five header pins.

> Nothing here has been tested on the actual boards yet. The Audio Kit pin numbers come from
> community board definitions (sources at the end) and the kit ships in revisions that differ.
> Phase 0 is there to confirm your revision before any wire goes on.

## Why an extra part is needed

The Audio Kit's header has only five usable pins (`IO5`, `IO18`, `IO19`, `IO22`, `IO23`). Two
readers and two encoders need ten signals. So:

* the two encoders (six signals) go on an **MCP23017 port expander**, which talks to the kit
  over two wires (I2C) plus one "something changed" wire;
* **reader B shares those two I2C wires**, since it has a different address from the expander;
* **reader A uses the last two pins** as a serial port. Both readers have the same fixed I2C
  address, so they cannot both sit on the I2C wires.

## What each part is for

| Part | Role | Used |
|---|---|---|
| ESP32-Audio-Kit | Brain, codec, 2 × 3 W amplifier, SD slot, LiPo charger | 1 |
| PN532 V3, **reader A** | Card slot. Serial (HSU) mode | 1 |
| PN532 V3, **reader B** | Sticker boop spot. I2C mode | 1 (so there is no spare reader any more) |
| KY-040 encoder 1 | Volume (turn), play / pause (press) | 1 |
| KY-040 encoder 2 | Back / forward in the song (turn), press is free | 1, three spares |
| MCP23017 expander module | Reads both encoders | 1, **to buy** |
| 4 Ω 3 W speaker | Plugs into one speaker output | 1 |
| 3.7 V 3000 mAh LiPo | Plugs into the BAT socket, charged by the kit over USB | 1 |
| 64 GB microSD | Audio files. Reformat as **FAT32** first (it ships as exFAT, which the ESP32 SD libraries don't read by default; Windows needs a tool such as Rufus or guiformat for FAT32 above 32 GB) | 1 |
| Mini breadboards | Power and I2C junctions for the bench test | 2 |
| Lever connectors | 3V3 and GND hubs in the final build | 2, one spare |
| JST PH2.0 / XH2.54 leads | Speaker and battery plugs. Only one size fits the kit's sockets, see Phase 0 | 2 |

### To buy

* **MCP23017 I2C port expander module** (a small breakout board with pins, sold as "MCP23017
  module" or "CJMCU-2317"). Get two, they cost very little and one is a spare.
* **Slide or rocker switch** (rated 1 A or more). The kit has no power switch: with the battery
  plugged in, it is on. For the first tests, unplugging the battery is the switch.

A multimeter is already to hand; the battery step depends on it.

### About the cards

The CUID cards, the white card and the blue fob are Mifare Classic 1K. NFC stickers are usually
NTAG213 / 215. The box reads both, and identifies each by its UID exactly as the phone app does,
so the same card or sticker can play on both. The app's **Write to card** and "tap opens BoopBox"
need NDEF tags (NTAG); on Mifare Classic they only work on phones with an NXP NFC chip.

## Pin map

Audio Kit header:

| Header pin | Signal | Goes to | Notes |
|---|---|---|---|
| `3V3` | 3.3 V | Both readers `VCC`, expander `VCC`, both encoders `+` | **Never 5 V.** The modules pull their signal lines up to their supply pin |
| `GND` | Ground | Every module's `GND` | |
| `IO19` | Serial RX | Reader A `TXD` (the pad printed `SDA` on the front) | Shared with KEY3 |
| `IO22` | Serial TX | Reader A `RXD` (the pad printed `SCL` on the front) | Shared with LED D4, which flickers while reader A is polled |
| `IO18` | I2C data (SDA) | Reader B `SDA` **and** expander `SDA` | Shared with KEY5 |
| `IO23` | I2C clock (SCL) | Reader B `SCL` **and** expander `SCL` | Shared with KEY4 |
| `IO5` | "Knob moved" | Expander `INTA` (also printed `ITA`) | Shared with KEY6 |

Expander (labels vary: `GPA0`, `PA0` or `A0` for the port pins):

| Expander pin | Goes to |
|---|---|
| `GPA0` | Encoder 1 (volume) `CLK` |
| `GPA1` | Encoder 1 `DT` |
| `GPA2` | Encoder 1 `SW` |
| `GPA3` | Encoder 2 (back / forward) `CLK` |
| `GPA4` | Encoder 2 `DT` |
| `GPA5` | Encoder 2 `SW` |
| `RESET` | `3V3`, unless the module already pulls it up |
| Address pins `A0` `A1` `A2` (also `AD0`…) | `GND`, unless the module already pulls them down |
| `GPA6`, `GPA7`, all of `GPB`, `INTB` | empty (ten spare inputs for later buttons) |

Reader mode switches (the two tiny switches on each PN532):

| Reader | Switch 1 | Switch 2 | Mode |
|---|---|---|---|
| A, card slot | OFF | OFF | HSU (serial) |
| B, sticker spot | **ON** | OFF | I2C |

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
| Codec control (I2C, a separate bus from the one above) | SDA 33, SCL 32 |
| Codec audio (I2S) | MCLK 0, BCK 27, WS 25, DOUT 26, DIN 35 |
| SD card | CLK 14, CMD / MOSI 15, D0 / MISO 2, D3 / CS 13 (D1 4, D2 12 in 4-bit mode) |
| Headphone detect | 39 |
| KEY1 (stays usable as a spare button) | 36 |

On both PN532s, `SCK`, `MISO`, `MOSI`, `SS`, `IRQ`, `RSTO` stay unconnected.

```
                                 ESP32-Audio-Kit
                              +-------------------+
  READER A (card slot)        |                   |
  switches OFF OFF            |                   |
    TXD (SDA pad) ------------| IO19              |
    RXD (SCL pad) ------------| IO22              |
                              |                   |
  READER B (sticker spot)     |                   |        MCP23017 EXPANDER
  switches ON OFF             |                   |       +----------------+
    SDA ---------------+------| IO18              |       |                |
    SCL ------------+--|------| IO23              |       |                |
                    |  +------|-------------------|-------| SDA   GPA0 ----|-- CLK  ENCODER 1
                    +---------|-------------------|-------| SCL   GPA1 ----|-- DT   (volume)
                              | IO5 --------------|-------| INTA  GPA2 ----|-- SW
                              |                   |       |                |
                              | 3V3 --> every VCC and +   |       GPA3 ----|-- CLK  ENCODER 2
                              | GND --> every GND         |       GPA4 ----|-- DT   (back /
                              |                   |       |       GPA5 ----|-- SW    forward)
      Speaker ====JST=========| SPK (either one)  |       +----------------+
      LiPo ==switch==JST======| BAT               |
                              +-------------------+
        empty on the kit: IO21, IO0, TX0, RX0, RST
```

## Phase 0: checks before any wire goes on

Power the kit from USB only. The battery stays in its bag until Phase 1 step 8.

1. **Read the header silkscreen.** Find the pins printed `3V3`, `GND`, `IO5`, `IO18`, `IO19`,
   `IO22`, `IO23`. Go by the printed labels, not by position.
2. **Set the kit's DIP switches** to `1 OFF, 2 ON, 3 ON, 4 OFF, 5 OFF`. Switches 2 and 3 hand
   GPIO 15 and 13 to the SD card, 1 would give GPIO 13 to KEY2, 4 and 5 are for a JTAG debugger.
3. **Set the reader switches** as in the table above and label the two boards **A** and **B**
   with tape or a marker. The print next to the switches reads `0 0` for HSU and `1 0` for I2C.
4. **Solder the 4-pin header** (`GND VCC SDA SCL`) onto **both** PN532s. Push the pins through
   so they stick out on the **chip side**; the other side stays flat and later sits against the
   lid. Iron at 330 °C, a touch of flux, heat pad and pin together for two seconds, feed solder,
   remove solder, then iron. Each joint should be a small shiny cone, and no two neighbouring
   pins may beep on the multimeter's continuity setting. There is no spare reader now, so take
   the first joint slowly.
5. **Flip reader A over** and read the back: the `SDA` and `SCL` pads are printed `TXD` and
   `RXD` there. Those are the names the pin map uses for reader A.
6. **Solder the expander's header** if it comes loose in the bag.
7. **Test-fit the JST plugs, unpowered.** Try a PH2.0 and an XH2.54 plug in the `BAT` socket and
   in a speaker socket. The right one slides in and clicks with no force (PH has 2.0 mm between
   pins, XH 2.5 mm). I expect PH2.0 for all three sockets, but sellers list both.
8. **Find the revision:** flash `tests/01_i2c_scan`. Most ES8388 kits have the codec on GPIO
   33 / 32, which is what this plan assumes. An older run has it on 18 / 23 with the audio clock
   on 5: on that one three of the five pins above are taken and this plan cannot work as drawn.
   **Stop if the sketch says so.**

## Phase 1: bench test

One thing at a time, each proved before the next goes on. Unplug USB before adding or moving
any wire. The sketches are in [`tests/`](tests/README.md).

**Step 1. Kit alone.** Sketch `01_i2c_scan`.
*Checkpoint:* `PASS: ES8388 ... on SDA 33 / SCL 32`.

**Step 2. Speaker.** If the speaker has bare tabs, solder a JST lead of the fitting size to them
(be quick, the tabs sit in plastic), then plug it into either speaker socket. Polarity does not
matter with one speaker. Sketch `02_tone`.
*Checkpoint:* beeps on the left channel, the right, then both. The speaker sounds on one of the
first two, which tells you which socket it is in.

**Step 3. SD card.** FAT32-formatted, with a `test.wav` on it (see the tests README), pushed in
until it clicks. Sketch `03_sd_card`.
*Checkpoint:* the file list prints and `test.wav` plays.

**Step 4. Power and I2C junctions.** On a mini breadboard every column of five holes is joined.
3V3 and GND each need seven connections, so each gets two columns bridged by a male to male
jumper. The I2C wires get one column each.

```
   column:    1     2          4     5          7        8
            +---+ +---+      +---+ +---+      +---+    +---+
            |3V3|=|3V3|      |GND|=|GND|      |SDA|    |SCL|
            +---+ +---+      +---+ +---+      +---+    +---+
            "=" is a male-to-male jumper joining the two columns

   3V3 columns:  kit 3V3, reader A VCC, reader B VCC, expander VCC, encoder 1 +, encoder 2 +
   GND columns:  kit GND, reader A GND, reader B GND, expander GND, encoder 1 GND, encoder 2 GND
   SDA column:   kit IO18, reader B SDA, expander SDA
   SCL column:   kit IO23, reader B SCL, expander SCL
```

Connect only kit `3V3` and kit `GND` first (female end on the kit, male end in the breadboard),
plug USB in and measure between a 3V3 column and a GND column.
*Checkpoint:* about 3.3 V. 5 V or 0 V means the wrong header pin.

**Step 5. Reader A (serial).**

| From | To | Jumper |
|---|---|---|
| 3V3 column | Reader A `VCC` | male to female |
| GND column | Reader A `GND` | male to female |
| Kit `IO19` | Reader A `TXD` (`SDA` pad) | female to female |
| Kit `IO22` | Reader A `RXD` (`SCL` pad) | female to female |

Sketch `04_nfc`.
*Checkpoint:* `Reader A: found PN532`, then `A: card on <UID>` each time a card or the fob is
held to it. Reader B is reported missing, which is right for now.
*If reader A does not answer:* swap the `IO19` and `IO22` wires (crossed TX / RX is the usual
cause), then check both its switches are `OFF`.

**Step 6. Reader B (I2C).**

| From | To | Jumper |
|---|---|---|
| 3V3 column | Reader B `VCC` | male to female |
| GND column | Reader B `GND` | male to female |
| Kit `IO18` | SDA column | female to male |
| Kit `IO23` | SCL column | female to male |
| SDA column | Reader B `SDA` | male to female |
| SCL column | Reader B `SCL` | male to female |

Same sketch, `04_nfc`.
*Checkpoint:* both readers found, and each reports only the cards held to it. With USB in and
nothing on the readers, the SDA and SCL columns each measure about 3.3 V against GND.
Now find the spacing: move the two readers towards each other until a card on one starts to
show on the other or reads become unreliable, and note the distance. The enclosure must keep
them further apart than that. Note each reader's reading distance too; it sets the lid thickness.
*If reader B does not answer:* check switch 1 is `ON`, then swap its `SDA` and `SCL` wires.

**Step 7. Expander and both encoders.** (Waits for the MCP23017 to arrive.)

| From | To | Jumper |
|---|---|---|
| 3V3 column | Expander `VCC` | male to female |
| GND column | Expander `GND` | male to female |
| SDA column | Expander `SDA` | male to female |
| SCL column | Expander `SCL` | male to female |
| Kit `IO5` | Expander `INTA` | female to female |
| 3V3 column | Expander `RESET` | male to female (skip if the sketch finds the expander without it) |
| GND column | Expander `A0`, `A1`, `A2` address pins | only if the sketch can't find the expander |
| 3V3 column | Encoder 1 `+`, encoder 2 `+` | male to female, one each |
| GND column | Encoder 1 `GND`, encoder 2 `GND` | male to female, one each |
| Expander `GPA0`, `GPA1`, `GPA2` | Encoder 1 `CLK`, `DT`, `SW` | female to female |
| Expander `GPA3`, `GPA4`, `GPA5` | Encoder 2 `CLK`, `DT`, `SW` | female to female |

Sketch `05_encoders`.
*Checkpoint:* `Expander found at 0x20` (0x21 to 0x27 are fine too, except 0x24, which is reader
B's address: the address pins must not be set to that). Each click of knob 1 prints
`Knob 1: +1` or `-1`, the same for knob 2, and each press prints once. A knob that counts
backwards has its `CLK` and `DT` wires swapped. Then run `04_nfc` again: reader B still works
with the expander on the same two wires.

**Step 8. Battery, last.** USB unplugged.

1. Check the cell has a protection board (a small strip under the tape where the wires leave).
   A bare cell with none should not go in a child's toy.
2. Find `+` and `−` printed next to the `BAT` socket.
3. With the multimeter on DC volts (20 V range), red probe on the plug contact that will land on
   the board's `+`, black on the other. `+3.7` to `+4.2` V is right. A **negative** reading
   means the plug is reversed: do not plug it in. Do this even if the battery came with a plug
   that fits, as ready-made plugs are often the other way round.
4. If it is reversed, or the battery has bare wires, join it to the fitting JST lead **one wire
   at a time**: cut, slide heat shrink on, solder, shrink, and only then touch the second wire.
   The two battery wires must never be bare at the same moment.
5. Plug the battery in.

*Checkpoint:* the kit runs with USB unplugged. Plug USB back in and the charge LED lights. Expect
a full charge of 3000 mAh through the kit's small charger to take many hours.

**Step 9. All together.** Sketch `06_all_together`, on battery, for an hour: a card in the slot
or a sticker on the spot starts the sound, taking it away stops it, knob 1 changes the volume,
knob 2 jumps back and forward. The sketch prints the uptime every minute and the reason for the
last restart, so a brown-out at full volume shows up.

## Phase 2: final build

The connections are the same. What changes is how they are held.

1. **Plan the positions first**, and test with the parts taped in place before drilling.

   ```
        card slot                          sticker spot
     =============================================== lid / wall: 3 mm or less, no metal
     [ reader A ]   <-- further apart than the -->  [ reader B ]
                        distance found in step 6

     [speaker]      [Audio Kit on standoffs]      [battery on foam tape]
   ```

   * Each reader flat side against the inside of the wall where its card or sticker goes, with
     no metal, foil or metallic paint over the antenna.
   * At least 3 cm between each reader and the speaker magnet, the battery and the Audio Kit.
     Metal close to an antenna shortens the reading distance.
   * The two readers further apart than the distance found in step 6, and not facing each other.
   * Audio Kit against a wall with both USB sockets reachable (charging and updates) and the SD
     card removable.
   * Battery where nothing can press into it: no screw tips, no standoff ends, no solder joints
     facing it. Hold it with double-sided foam tape, never a clamp.
2. **Mount** the Audio Kit, both readers and the expander on M3 standoffs. Nylon standoffs go
   next to the reader antennas, not steel. The encoders go through the wall by their shafts, or
   on standoffs through their two holes.
3. **Power hubs: two lever connectors**, one for 3V3 and one for GND, five wires each. First
   join the two encoders' `+` wires into one tail with a soldered Y splice under heat shrink,
   and the same for their `GND` wires. Each lever connector then takes: kit, reader A, reader B,
   expander, encoder pair. Strip 10 mm, twist the strands, fold the bare end back on itself so
   the thin wire is doubled, clamp, and tug each wire. Mark the two connectors so they can't be
   mixed up.
4. **I2C junctions: two soldered splices.** `IO18`, reader B `SDA` and expander `SDA` twisted
   together, soldered and covered in heat shrink; the same for `IO23` and the two `SCL` wires.
   Keep these three-way joins short: none of the I2C wires longer than about 20 cm.
5. **Keep the plugs on the Audio Kit header** rather than soldering to it, so the board stays
   removable. Where 10 cm is too short, join two jumpers with a soldered splice under heat
   shrink, not with a male end pushed into a female one.
6. **Power switch** (once it arrives) in the battery's **red** wire: battery red to one switch
   lug, the next lug to the red wire of the JST lead, heat shrink over both lugs. Black goes
   straight through. Same one-wire-at-a-time rule as before. The switch must be on for the
   battery to charge.
7. **Tie it down.** Group the jumpers on each header with a small cable tie or a wrap of tape so
   a drop can't work one loose. Leave a little slack in every wire, and keep the speaker lead
   away from both readers.
8. **Close-up test** before the last screw: `06_all_together` at full volume on battery for ten
   minutes, then shake the box. Any restart or crackle means a loose plug, splice or lever
   connector.

## Known limits of this hardware

* **Every usable header pin is taken.** Further buttons go on the expander's ten spare inputs.
  KEY1 on the kit is also still free.
* **Only one reader's field is on at a time.** Firmware polls A, switches its field off, polls
  B, and so on, a few times a second each. That keeps the two from disturbing each other and
  halves the current they draw from the kit's 3.3 V supply.
* **No battery level.** None of the published pin lists show the battery voltage reaching the
  ESP32, and no header pin can measure a voltage, so firmware cannot warn about a low battery
  without an extra part.
* **No true off without the switch.** Firmware sleep still leaves the codec, USB chip and
  regulator drawing current, so a box left "asleep" slowly empties the cell.
* **KEY3 to KEY6 on the kit** share pins with the readers and the expander. Don't press them
  while the box runs: they would disturb the reader or knob signals.
* **Volume on battery** is likely lower than on USB, since the cell gives 3.7 V where USB gives
  5 V. If the box restarts at full volume on battery, cap the volume in firmware.

## Sources

* Audio Kit pins per revision, DIP switches, SD pins:
  [arduino-audiokit wiki, GPIO overview](https://github.com/pschatzmann/arduino-audiokit/wiki/GPIO-Overview-by-Selected-Board),
  [SD drive and conflicting buttons](https://github.com/pschatzmann/arduino-audiokit/wiki/Problems-with-the-SD-Drive-or-some-conflicting-Buttons)
* Keys, LED, amplifier enable, headphone detect:
  [esp32-a1s-sendspin](https://github.com/el-bakkali/esp32-a1s-sendspin),
  [squeezelite-esp32 issue 123](https://github.com/sle118/squeezelite-esp32/issues/123)
* PN532 V3 mode switches, supply range and shared I2C / HSU pads: the Elechouse PN532 V3 manual,
  as quoted by resellers ([Elecrow](https://elecrow.com/pn532-nfc-module-pn532-rfid-v3-module.html))
