# Aeroglyph

**Messages, carried on sound.** One Android phone speaks a short message into the air; every phone in earshot decodes it. No internet, no Wi-Fi, no Bluetooth, no location, no pairing, no accept prompt, no external hardware. Just a speaker and a microphone.

Built for **PS02 — Acoustic One-to-Many Communication**.

---

## What makes this different

Most acoustic-modem demos stop at "text goes in, text comes out." Two things take Aeroglyph past that:

### 1. Echo Relay — an acoustic mesh, not just a broadcast

Every frame carries a TTL. A device that decodes a message rebroadcasts it exactly once, with the hop count decremented, after a randomized 400–900 ms delay so simultaneous relays don't collide.

That turns one-to-many broadcast into **genuine ad hoc mesh networking with no network stack** — a phone that is far out of the broadcaster's range still gets the message, carried the last leg by someone else's speaker.

Loop prevention costs nothing extra: the same "have I seen session X?" set that stops a receiver displaying four copies of a repeated frame also stops it relaying one twice, and stops two devices bouncing a message back and forth forever.

Relay can be switched off, which returns the app to exactly the plain one-hop behaviour the base spec describes.

### 2. Generative Receipt Glyphs — a mark, not a checkmark

Every decoded message leaves behind a generated vector mark, drawn live on a Compose canvas from two seeds:

- the **session ID** drives the structure (ring count, sweeps), so every device that received the same broadcast shows a visibly *related* mark;
- the **device's own receipt ID** drives the accents (spokes, dots), so each phone's copy is still its own.

A room full of phones ends up holding a family of marks that are clearly siblings and clearly individual. That is the demo moment.

---

## The rest of the design

- **A real instrument, not a spinner.** The 16-bar spectrum on every screen is driven by actual Goertzel magnitudes at the 16 tone frequencies the decoder is listening on — during a transmission you watch symbols land bin by bin. While transmitting, it switches to the outgoing PCM, so the bars are never faked.
- **Custom theme, no Material defaults.** Dark-first "signal lab" palette; deliberately *not* Material You dynamic colour, because recolouring from the wallpaper would destroy the one thing that makes every screen recognisably this app.
- **Three bundled typefaces, one convention.** Space Grotesk for headings, Inter for prose, and **IBM Plex Mono for every number, ID, frequency, and code** — applied consistently enough that "if it's data, it's mono" reads as a rule rather than an accident.
- **Fonts and icons ship inside the APK.** No Google Fonts downloadable-provider, no icon font: both need a network fetch, and an app whose whole premise is "works with no network" should not go online to draw its own text.

---

## Running it

### If you have never built an Android app before

1. **Install Android Studio** — <https://developer.android.com/studio>. Accept the default install; it bundles its own JDK and Gradle, so you do not need to install Java separately. (The JDK on your machine right now is Java 8, which is too old — Android Studio's bundled JDK 17 is what will be used.)
2. **Open the project** — launch Android Studio → *Open* → select this folder (`C:\Hackathon`). Do not use *Import*; this is already a Gradle project.
3. **Let it sync.** The first Gradle sync downloads Gradle 8.9, the Android Gradle Plugin, and the Compose libraries. This takes several minutes on a first run and needs internet *once* — the app itself never does.
4. **Install the SDK if prompted.** Android Studio will offer to install SDK Platform 35 and build tools. Accept.
5. **Enable developer mode on your phone** — Settings → About phone → tap *Build number* seven times → back → Developer options → enable **USB debugging**.
6. **Plug the phone in**, pick it in the device dropdown at the top, and press ▶ **Run**.

Repeat steps 5–6 for each phone you want to test with — you need at least two, and three to show Echo Relay properly.

### Command line

```bash
./gradlew test            # JVM modem tests, no emulator or device needed
./gradlew assembleDebug   # produces app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. If `./gradlew test` complains about the JDK, point it at Android Studio's bundled one:

```bash
./gradlew test -Dorg.gradle.java.home="C:\Program Files\Android\Android Studio\jbr"
```

> **Verified.** This project has been built and tested end-to-end on a real toolchain (JDK 17, Android SDK Platform 35): `./gradlew test` passes all 16 JVM unit tests (Crc32Test, FecCodecTest — including the exhaustive single/double-bit-flip cases — and AudioModemRoundTripTest, noisy-channel cases included), and `./gradlew assembleDebug` produces a working, installable APK. The Compose UI, theme, and every screen also compile cleanly with zero warnings. What has *not* been verified is behavior on a physical device — the multi-device Echo Relay demo, real microphone/speaker characteristics, and permission-flow UX all still want a real phone.

---

## Testing it

### Two devices — the basic path

1. Run the app on both. Grant the microphone permission on each.
2. Device A → **Broadcast**. Device B → **Listen**.
3. Type something on A (try `Welcome to the demo — aeroglyph.dev`) and press **Broadcast**.
4. Hold the phones ~30 cm apart for the first try. B should show the message, a glyph, and `CRC VERIFIED`.
5. Now walk B back to 2 m, then 5 m, and repeat.

### Three devices — the Echo Relay demo

This is the one worth showing a judge.

1. Turn **Echo Relay** on in Broadcaster → *Advanced* (it is on by default, TTL 2).
2. Place A (broadcaster) and C (receiver) far enough apart, or with enough between them, that C cannot hear A directly — verify this first by broadcasting with relay **off** and confirming C gets nothing.
3. Put B roughly between them, in **Listen** mode.
4. Turn relay back on and broadcast from A.
5. B decodes and shows `DIRECT`. Roughly a second later B rebroadcasts, and **C decodes and shows `VIA RELAY`** with one less TTL.
6. Open the **Signal log** on each device to show the hop path.

To show loop prevention: add a fourth device D in range of both A and B. D receives A's original, and then ignores B's relay of the same session instead of relaying it onward.

### Robustness checks

- **Repetition** — start a broadcast, kill and reopen the receiver app mid-transmission. It catches a later repetition (frames repeat 4× by default).
- **Noise** — play music or talk over the transmission. Corrupted frames fail CRC and are dropped silently; a garbled message is never displayed. A later clean repetition still gets through.
- **Room profiles** — Broadcaster → *Advanced* → **Noisy** drops to 30 symbols/sec with 6 repetitions. Slower and more redundant; use it in a loud room.
- **Confirmation mode** — turn it on and watch `DEVICES CONFIRMED` count unique acoustic ACKs.
- **Interruptions** — call the phone mid-broadcast. Audio focus is released and the transmission stops cleanly rather than half-sending a frame.

---

## How it works

```
Text ─► UTF-8 ─► [len | session | type+TTL]  +  payload  +  CRC-32
                          │                        │
                          └── Hamming(8,4) SECDED ─┘
                                     │
                          4 bits/symbol ─► 16-FSK tones (17–19.5 kHz)
                                     │
              [sync chirp] ─► [header block] ─► [payload block] ─► [end chirp]
                                     │
                                 AudioTrack ──))) air (((── AudioRecord
                                                              │
                          chirp cross-correlation ─► symbol clock
                                                              │
                          Goertzel at 16 known bins ─► nibbles ─► bytes
                                                              │
                          FEC correct ─► CRC verify ─► deliver / drop
```

**Frame layout** — header is 3 bytes (`payload length`, `session ID`, `frame type << 6 | hop count`), then payload, then CRC-32. The header is sent as its own fixed-size FEC block *first*, so a receiver can decode just those 12 symbols, learn the payload length, and only then know how many more symbols to listen for.

**Source map**

| Path | What lives there |
|---|---|
| `audio/ModemConfig.kt` | Every constant both ends must agree on, plus room profiles |
| `audio/Frame.kt` | Frame model, header packing, frame types |
| `audio/Crc32.kt` | CRC-32 |
| `audio/FecCodec.kt` | Extended Hamming(8,4) SECDED |
| `audio/ChirpSync.kt` | Chirp generation + cross-correlation detection |
| `audio/DspUtil.kt` | Goertzel, raised-cosine envelope, RMS |
| `audio/AudioEncoder.kt` | Frame → PCM (16-FSK modulator) |
| `audio/AudioDecoder.kt` | PCM → Frame (Goertzel demodulator) |
| `playback/Transmitter.kt` | AudioTrack, repetition, audio focus |
| `playback/Listener.kt` | AudioRecord, VAD gate, throttled decode attempts |
| `playback/Feedback.kt` | Haptics + synthesized confirmation chime |
| `session/SessionManager.kt` | Dedupe / relay-loop prevention |
| `session/SignalLog.kt` | In-memory history and decode stats |
| `ui/AeroglyphViewModel.kt` | Where decode events become relay, ACK, log, feedback |
| `ui/theme/` | Palette, type scale, shapes |
| `ui/components/ReceiptGlyph.kt` | The generative mark |
| `ui/components/SpectrumVisualizer.kt` | The 16-bin readout |

---

## Deliberate deviations from the brief

Both were taken under the brief's own tie-breaker — *when a constraint and a nice-to-have conflict, favour reliability*.

1. **The header and CRC are FEC-protected too.** Read literally, the spec puts the header before the FEC stage and the CRC after it, leaving both bare on the wire. But a single flipped bit in the length byte desynchronises the entire parse, and a flipped bit in the CRC rejects a message that arrived perfectly. Everything is protected uniformly instead.

2. **Hamming(8,4) SECDED instead of Hamming(7,4).** The spec asks `decode()` to return `null` on an uncorrectable error, but plain (7,4) has no way to *detect* a double-bit error — it silently miscorrects into a wrong value. Adding one overall parity bit per nibble buys guaranteed double-error *detection*, which is what makes that `null` mean anything. It also happens to make each nibble map to exactly one byte, so the modulator needs no bit-packing at all. `FecCodecTest` verifies both properties exhaustively.

---

## Known limitations

- **Near-ultrasonic, not ultrasonic.** True >20 kHz is unreliable across cheap phone speakers and mics, so the band is 17–19.5 kHz. Most adults won't notice it; some people (and most dogs) will.
- **Range and noise.** Reliability degrades with distance, ambient noise, and obstructions. FEC and repetition mitigate this; they don't eliminate it. Echo Relay is the answer to range, not raw transmit power.
- **ACK confirmation is best-effort.** If many devices ACK at once their bursts overlap and some are lost, so the tally can read low in a busy room. Each receiver's own glyph is the authoritative confirmation — the app says so in-app rather than hiding it.
- **8-bit session IDs.** 256 values, tracked in memory, capped at 128 entries. Fine for a demo; a long-lived deployment would want wider IDs with time-based expiry.
- **Session state is in-memory only.** The signal log and dedupe set are cleared when the app dies. This is deliberate — Aeroglyph writes no audio and no message content to disk, ever.
- **One frame at a time.** Two broadcasters transmitting simultaneously will collide. Distinct session IDs stop the fragments being stitched into one garbled message, but neither may decode.
- **Foreground only.** Listening runs while the app is open. Background listening would need a foreground service.

---

## Licenses

Bundled third-party assets, with license texts in [`licenses/`](licenses/):

- **Space Grotesk**, **Inter**, **IBM Plex Mono** — SIL Open Font License 1.1
- **Lucide** icons — ISC License (converted to Android vector drawables)
- **Gradle wrapper** — Apache License 2.0

Everything else in this repository was written for PS02.
