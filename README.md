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

### 3. Self-healing delivery — the two surprise challenges

Both challenges reduce to the same question: *somebody is missing something -- who answers, and when?* So they are answered by one gossip layer rather than two bolted-on features.

**Partial reception.** The header block is FEC-protected separately from the payload, so a frame damaged in transit usually still yields a readable header. That is the difference between "something went wrong" and "session 6D is damaged" -- and the second one can be acted on. A receiver in that state emits a `NACK` naming the exact session, and any device holding that message re-sends it. The original sender has no special role, so recovery works even if the sender has walked out of range.

**Dynamic group.** A device that opens the Listen screen immediately asks the room `REQUEST(latest)`. Anyone holding a message answers. Devices holding a message also emit a periodic `BEACON` advertising the session they have, so a late arrival whose request went unheard still learns something exists and asks for it by name. Either way a phone that arrives after the broadcast ends catches up on its own, with nobody touching the sender.

**What stops the shouting match.** Every answer waits a random 0.5-1.8s and is cancelled if a neighbour is heard answering first, and the same session will not be re-served within a cooldown. Twenty phones holding the message produce roughly one answer, not twenty.

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

> **Verified.** Built and tested on a real toolchain (JDK 17, Android SDK Platform 35): `./gradlew test` passes all 46 JVM unit tests and `./gradlew assembleDebug` produces an installable APK. Twelve of those tests drive the whole receive path with synthesised audio -- weak signals, mid-chunk arrivals, noise in the wrong band, false onsets -- because every reception bug this project has had was invisible to the codec tests and only showed up on a phone. What is *not* verified is on-device acoustic behaviour: real microphone and speaker response in either band, and how far the range actually stretches in a given room. The ~25 m figure is derived from air-absorption and transducer-response figures, not measured on hardware.

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

### Surprise challenge 1 — partial reception repairs itself

1. Two devices, A broadcasting and B listening, far enough apart that reception is marginal (or play music between them).
2. When B catches a frame whose header survives but whose payload does not, it shows **"Damaged frame — requesting repair"** and its Signal log gains a red `REPAIR ASKED` row.
3. A (or any third device that already has the message) answers, and B ends up showing the message with a **RECOVERED** badge.
4. The point to make out loud: nobody pressed anything on A. Point at A's `REQUESTS ANSWERED` counter.

To prove the sender is not special, do it again with a third device C holding the message and A's app closed. B still recovers -- from C.

### Surprise challenge 2 — a device that joins late catches up

1. A broadcasts a message to B. Wait for it to finish completely.
2. **Now** open the app on C, which was not running during the broadcast, and tap **Listen**.
3. C asks the room automatically -- **"Asking the room for the latest"** -- and within a few seconds displays the message with a **RECOVERED** badge, having never heard the original broadcast.
4. Again: nothing was re-triggered on A. Whichever of A or B is closest answers.

If C is out of earshot of everyone at the moment it asks, it stays quiet and waits: any holder emits a beacon every 20 seconds, and C will ask again as soon as it hears one.

### Robustness checks

- **Repetition** — start a broadcast, kill and reopen the receiver app mid-transmission. It catches a later repetition (frames repeat 4× by default).
- **Noise** — play music or talk over the transmission. Corrupted frames fail CRC and are dropped silently; a garbled message is never displayed. A later clean repetition still gets through.
- **Room profiles** — Broadcaster → *Advanced* → **Noisy** drops to 30 symbols/sec with 6 repetitions. Slower and more redundant; use it in a loud room.
- **Long range (~25 m)** — Broadcaster → *Advanced* → **Long range**. Moves the carrier to 10–14.5 kHz and slows to 15 symbols/sec. **This mode is audible.** Receivers need no configuration at all — they work out the band from the chirp — so you can leave every other phone exactly as it is and just change the sender.
- **Confirmation mode** — turn it on and watch `DEVICES CONFIRMED` count unique acoustic ACKs.
- **Interruptions** — call the phone mid-broadcast. Audio focus is released and the transmission stops cleanly rather than half-sending a frame.

---

## How it works

```
Text ─► UTF-8 ─► [len | session | type+TTL]  +  payload  +  CRC-32
                          │                        │
                          └── Hamming(8,4) SECDED ─┘
                                     │
                          bit interleave (1 symbol error ─► 1 bit/codeword)
                                     │
                          4 bits/symbol ─► 16-FSK tones
                                            17–19.5 kHz silent, or
                                            10–14.5 kHz for ~25 m
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

**Frame layout** — header is 3 bytes (`payload length`, `session ID`, `frame type << 5 | hop count`), then payload, then CRC-32. The header is sent as its own fixed-size FEC block *first*, so a receiver can decode just those 12 symbols, learn the payload length, and only then know how many more symbols to listen for. That staging is also what makes targeted repair possible: a frame whose payload is destroyed still usually yields a readable header, so the receiver knows exactly which session to ask for.

**Frame types** (3 bits): `DATA`, `RELAY` (mesh rebroadcast), `ACK` (confirmation mode), `NACK` (repair request), `REQUEST` (catch-up), `BEACON` (I hold session X), `ANSWER` (a message re-sent to satisfy a request).

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
| `session/MessageStore.kt` | What we hold, plus who answers a repair/catch-up request and when |
| `session/SignalLog.kt` | In-memory history and decode stats |
| `ui/AeroglyphViewModel.kt` | Where decode events become relay, ACK, log, feedback |
| `ui/theme/` | Palette, type scale, shapes |
| `ui/components/ReceiptGlyph.kt` | The generative mark |
| `ui/components/SpectrumVisualizer.kt` | The 16-bin readout |

---

## Deliberate deviations from the brief

All taken under the brief's own tie-breaker — *when a constraint and a nice-to-have conflict, favour reliability*.

1. **The header and CRC are FEC-protected too.** Read literally, the spec puts the header before the FEC stage and the CRC after it, leaving both bare on the wire. But a single flipped bit in the length byte desynchronises the entire parse, and a flipped bit in the CRC rejects a message that arrived perfectly. Everything is protected uniformly instead.

2. **Hamming(8,4) SECDED instead of Hamming(7,4).** The spec asks `decode()` to return `null` on an uncorrectable error, but plain (7,4) has no way to *detect* a double-bit error — it silently miscorrects into a wrong value. Adding one overall parity bit per nibble buys guaranteed double-error *detection*, which is what makes that `null` mean anything. It also happens to make each nibble map to exactly one byte, so the modulator needs no bit-packing at all. `FecCodecTest` verifies both properties exhaustively.

3. **The chirp sweeps 16.5-19.5kHz, not 1.8-19.5kHz.** The spec pins the wider sweep, but it broke reception outright, in two separate ways. The energy gate that decides "is a transmission happening" watches our own 16 tone bins, and a chirp spending its first 180ms below 17kHz is invisible to it -- the receiver only woke for the last sliver of the chirp, far too late to correlate against the template. Separately, correlation peak width goes as 1/bandwidth: a 17.7kHz sweep has a ~2.7-sample peak, narrow enough that any search step coarse enough to run in real time steps straight over it. Narrowing to 3kHz widens the peak to ~16 samples and puts the whole protocol inside the near-ultrasonic band, which is where it was always supposed to live.

4. **The receiver never discards audio on silence.** An earlier version cleared its buffer whenever input dropped below a threshold, which threw away the start of every transmission -- the sync chirp itself -- right up to the moment the gate opened. Capture now runs continuously into a ring buffer; the gate only decides *when to look*, never *what to keep*.

5. **The trigger threshold adapts rather than being a fixed number.** Microphone gain varies enormously across devices, and `UNPROCESSED` deliberately disables the AGC that would otherwise hide that. The floor now tracks measured background level and fires on a rise above it. The Listen screen shows SIGNAL, FLOOR and MARGIN live, so a room that is not working can be diagnosed by looking rather than guessing.

6. **Room profiles auto-negotiate.** Nothing on the wire announces which symbol rate the sender used, so a receiver set to a different profile would decode nothing with no indication why. The receiver now tries each profile's rate against the 12-symbol header and keeps whichever one its FEC agrees with -- no handshake, which the brief forbids, just three cheap attempts.

7. **The FEC is bit-interleaved before modulation.** This one was a genuine bug, not a judgement call. `FecCodec` emits one 8-bit SECDED codeword per nibble and the modulator takes 4 bits per symbol, so both halves of a codeword travelled as two *adjacent* symbols. But the error unit on an FSK channel is the symbol: when the Goertzel argmax picks the wrong tone, all four of that symbol's bits are wrong at once -- four bad bits in one codeword, far past what single-error correction can repair. Since one failed codeword fails its whole block, **a single wrong symbol discarded the entire message**, which is exactly the wrong failure curve for a long link where the realistic condition is "nearly all symbols are fine, a handful are not". A block transpose spreads each codeword's bits across eight symbols, so one bad symbol now deposits one correctable bit into each of four codewords and the frame survives. Costs nothing: same bits, same symbols, same airtime. `LongRangeTest` pins both the fix and the old behaviour it replaced.

7b. **The header is chosen by confidence, not by whichever rate the FEC tolerates first.** Nothing on the wire says which symbol rate the sender used, so the receiver tries each one. But "the FEC accepted it" is far weaker than it sounds: extended Hamming(8,4) treats 144 of 256 bytes as valid-or-correctable, so a six-codeword header of pure noise passes roughly 3% of the time — and across three candidate rates, about one lock in ten was being spent on a header that was never sent, silently discarding a frame that had arrived intact. Every candidate is now scored by how far each symbol's winning tone bin stands above the runner-up: correct alignment gives one steady tone per window and a large margin, a wrong rate smears energy across bins and collapses towards 1. The most confident candidate wins, and all of them must have their audio before any is chosen — a faster rate needs fewer samples and would otherwise always answer first.

7c. **The receive state machine is separated from the microphone.** `FrameDetector` is plain Kotlin that takes PCM chunks and emits frames; `Listener` does nothing but own the `AudioRecord` and pump it. This is not tidiness — the receive path is where every reception bug in this project has lived, and while it was reachable only through a live microphone it could not be tested at all. It is also why the band the gate picks is treated as a *hint* rather than a commitment: a stray noise landing just before a real transmission would otherwise claim the onset, guess the wrong band, and lose a frame sitting in the buffer.

8. **A second, audible carrier band for long range.** 25 m is not reachable at 17–19.5 kHz by tuning anything: air absorbs ~0.8–1 dB per metre up there (20–25 dB over 25 m, on top of ~14 dB of spreading loss from 5 m), and phone transducers are another 20–30 dB down at 19 kHz. That gap is 35–40 dB and the only place it exists is the carrier frequency. The **Long range** profile moves to 10–14.5 kHz, where absorption falls to ~0.3 dB/m and speakers are far stronger, and slows symbols 3× for another ~5 dB of integration gain. The ultrasonic band is untouched and still the default, because silence is a real feature — this is a mode, not a replacement. Bands are told apart purely by their (disjoint) sync chirps, so the receiver discovers the band rather than being configured.

---

## Known limitations

- **Near-ultrasonic, not ultrasonic.** True >20 kHz is unreliable across cheap phone speakers and mics, so the default band is 17–19.5 kHz. Most adults won't notice it; some people (and most dogs) will.
- **Range costs silence.** The ~25 m figure applies to the **Long range** profile only, and that profile is audible — a thin high warble for the duration of the transmission. The silent band remains a room-sized channel (~5–8 m), and no threshold tuning changes that; the limit is air absorption and speaker response, not software.
- **Range and noise.** Reliability degrades with distance, ambient noise, and obstructions. FEC, interleaving and repetition mitigate this; they don't eliminate it. Echo Relay extends coverage past any single device's reach.
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
