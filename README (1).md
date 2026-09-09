# PS02 — Acoustic One-to-Many Communication (Android)

**Track:** Android Development
**Problem Statement:** PS02 — Acoustic One-to-Many Communication

---

## 1. Problem Restatement

Broadcast a short message/URL from **one** Android device (Broadcaster) to **many** nearby Android devices (Receivers) using only the device's **built-in speaker and microphone** — no internet, no Wi-Fi, no Bluetooth, no location services, no external hardware, and no traditional pairing/accept flow.

### Hard Constraints → Design Response

| Constraint | Design Response |
|---|---|
| No internet / network infra | Pure audio channel; no signaling server |
| No Wi-Fi / Bluetooth / Location | `AudioRecord` + `AudioTrack` only; these permissions won't even be requested |
| Reliable despite some receivers missing the message | Forward Error Correction (FEC) + repeated transmission + CRC-checked retry logic |
| Confirm which devices received it | Optional **out-of-band ACK phase**: after broadcast, receivers that decoded successfully vibrate/show a QR-less on-screen code the broadcaster can visually tally, OR (if internet is available *later*, not required) — see §6 for the fully-offline confirmation approach we'll actually use |
| Simple join (no accept flow) | Receiver app just needs to be **open and listening** — no pairing dialog, no "accept incoming transfer" prompt |
| Efficient, no external hardware | Standard phone mic/speaker, ultrasonic-leaning band to reduce audible annoyance |

---

## 2. Core Technical Approach

### 2.1 Physical Layer — Acoustic Data Transmission (Audio-as-Modem)

We build a minimal **acoustic modem** (similar in spirit to Chirp/Google Tone/ggwave):

- **Modulation:** Multi-tone FSK (Frequency Shift Keying) — each symbol (e.g. 4 bits = 1 nibble) maps to one frequency out of 16, chosen from a near-ultrasonic band.
- **Frequency band:** ~17,000–19,500 Hz (near-ultrasonic). This range:
  - Is reproducible by most phone speakers and mics (typical mic Nyquist ~24 kHz at 48 kHz sample rate).
  - Is much less audible/annoying to humans than mid-range tones, while still being reliably captured by consumer mics (true ultrasonic >20 kHz is unreliable across cheap hardware).
- **Symbol rate:** Start conservative — 40–50 symbols/sec (20–25 ms per tone) to survive room echo/reverb; tune upward after testing.
- **Framing:**
  1. **Preamble/Sync chirp** — a fixed up-swept chirp (e.g., 1800 Hz → 19,500 Hz over 200 ms) so receivers can detect "a transmission is starting" and align their FFT windows (timing/frequency sync).
  2. **Header** — payload length + sequence/session ID.
  3. **Payload** — the message bytes, FEC-encoded.
  4. **CRC-16 / CRC-32** — appended for integrity check.
  5. **End marker** — a second, distinct chirp (down-swept) to mark end of frame.
- **Error correction:** Reed–Solomon (or simple Hamming(7,4) if time-constrained) applied to payload bytes before modulation, so single/burst symbol errors from ambient noise don't corrupt the whole message.
- **Repetition:** Broadcaster repeats the full frame **N times** (e.g., 3–5×) with short silence gaps; receiver accepts the first frame that passes CRC, making the system reliable "when some receivers fail to receive the message" (per constraint) — they simply catch it on repeat #2 or #3.

### 2.2 Sender (Broadcaster) Pipeline
```
Text/URL input
   → UTF-8 bytes
   → Reed-Solomon / Hamming FEC encode
   → Map to FSK symbol stream
   → Synthesize PCM samples (sine tones) via AudioTrack
   → Prepend sync chirp, append end chirp, wrap with CRC
   → Play N repetitions
```

### 2.3 Receiver Pipeline
```
AudioRecord (continuous listening, low-power VAD gate)
   → Sliding-window FFT (Goertzel algorithm preferred — cheaper than full FFT
     since we only need energy at 16 known frequency bins)
   → Sync-chirp detector (cross-correlation / energy-onset detector)
   → On sync detected: align symbol clock, decode each symbol window
     to strongest frequency bin → nibble
   → Reassemble bytes → FEC decode/correct → CRC check
   → If CRC passes: deliver message to UI, mark "Received" (dedupe if
     already received this session ID from a prior repetition)
   → If CRC fails: keep listening for next repetition
```

### 2.4 "Confirming which devices received it" (constraint)

Since we cannot use network/Bluetooth, true two-way confirmation back to the broadcaster is not literally possible over a *pure* one-way acoustic broadcast. Our interpretation/solution:
- Each receiver that successfully decodes shows a **large on-screen checkmark + short unique receipt code** derived from the session ID.
- The **broadcaster's screen displays a live "Devices Confirmed: N"** counter that increments via a **lightweight optional secondary acoustic back-channel**: once a receiver decodes successfully, it can (if the organizer chooses "confirmation mode") transmit a very short acoustic ACK burst of its own (a single unique short tone burst / device-ID chirp) that the broadcaster's own mic listens for. This keeps everything within "built-in audio capabilities" and does not require Wi-Fi/Bluetooth/network — satisfying the constraint literally.
- This is toggleable so a busy room doesn't get flooded with overlapping ACK tones — organizer can rely on the visual on-screen checkmark alone if confirmation mode is off.

---

## 3. App Architecture

**Language:** Kotlin
**Min SDK:** 24 (Android 7.0) — broad device coverage while keeping modern `AudioRecord`/`AudioTrack` behavior
**Target SDK:** latest stable

### Module/Package Layout
```
app/
 ├── ui/
 │   ├── BroadcasterScreen (compose or XML) — text/URL input, "Broadcast" button,
 │   │        live confirmation counter, waveform/level indicator
 │   ├── ReceiverScreen — "Listening…" state, decoded message display,
 │   │        receipt confirmation state
 │   └── ModeSelectScreen — choose Broadcaster / Receiver
 ├── audio/
 │   ├── AudioEncoder.kt      — text → PCM samples (FSK synth)
 │   ├── AudioDecoder.kt      — PCM samples → text (Goertzel + sync detection)
 │   ├── ChirpSync.kt         — sync/end chirp generation & detection
 │   ├── FecCodec.kt          — Reed-Solomon / Hamming encode-decode
 │   └── Crc.kt               — CRC16/32 helpers
 ├── playback/
 │   ├── Transmitter.kt       — wraps AudioTrack, plays frame N times
 │   └── Listener.kt          — wraps AudioRecord, streaming capture + VAD gate
 ├── session/
 │   └── SessionManager.kt    — session IDs, dedupe logic, ACK tallying
 └── MainActivity.kt / Nav
```

### Key Android APIs
- `AudioRecord` (raw PCM capture, `AudioSource.UNPROCESSED` or `VOICE_RECOGNITION` to avoid Android's built-in noise suppression/AGC mangling our tones)
- `AudioTrack` (raw PCM playback, `STREAM_MUSIC` or dedicated `AudioAttributes.USAGE_MEDIA`)
- Runtime permission: `RECORD_AUDIO` only
- Foreground consideration: listening can run while app is open; optionally a foreground service if organizers need "listen in background," though core requirement only needs app open

---

## 4. Performance, Reliability & Privacy (per general guidelines)

- **No image/audio raw data persisted** — audio buffers processed in-memory only, never written to disk (mirrors the "no captured camera images stored" spirit of the guidelines, applied here to mic data).
- **CPU/battery:** Use Goertzel algorithm (O(N) per target frequency) rather than full FFT — much cheaper for 16 known bins; gate active listening with a lightweight energy-threshold VAD so CPU idles when no transmission is happening.
- **Crash/edge-case handling:** handle mic permission denial, audio focus loss (e.g., phone call interrupts), device without adequate mic/speaker range (graceful degradation message), and multiple broadcasters overlapping (frame includes session ID to avoid stitching two different broadcasts together).
- **Stability targets:** decode success rate ≥ 90% at ~3–5 m in a moderately quiet room; message length target ~100–200 bytes (short text / URL) at ~5–8 seconds per repetition.

---

## 5. Build Plan / Milestones

| Phase | Deliverable | Est. Time |
|---|---|---|
| 1. Audio modem PoC | Standalone Kotlin/JVM test: encode string → WAV → decode back correctly on desktop | 3–4 hrs |
| 2. Android integration | Wire modem into `AudioTrack`/`AudioRecord`, test on 2 devices at 30 cm | 3–4 hrs |
| 3. Framing + FEC + CRC | Add sync chirp, FEC, CRC, repetition logic; test with background noise | 3 hrs |
| 4. UI (Broadcaster/Receiver) | Two clean screens, permission flow, live status indicators | 2–3 hrs |
| 5. Multi-device test | 3+ real devices simultaneously, varying distances/noise | 2 hrs |
| 6. ACK / confirmation mode | Optional back-channel + counter UI | 2 hrs |
| 7. Polish, error handling, APK build | Signed debug/release APK, README, demo script | 2 hrs |

---

## 6. Testing / Demo Script

1. Broadcaster enters "Welcome to XO Code 2026 — visit xocode.dev" and taps **Broadcast**.
2. 3–4 Receiver devices, screens showing "Listening…", placed at varying distances (0.5 m, 2 m, 5 m) with ambient room noise.
3. Verify each receiver displays the correct decoded text within the repetition window.
4. Kill/restart one receiver mid-transmission → confirm it catches the message on a later repetition.
5. Introduce noise (talking, music) → confirm FEC/CRC prevents corrupted message from being shown (fails silently, waits for clean repetition).
6. Toggle confirmation mode → confirm broadcaster's counter increments as receivers ACK.

---

## 7. Known Limitations (to state in submission per constraints)

- True ultrasonic (>20 kHz) is unreliable across budget device mics/speakers; we use near-ultrasonic (17–19.5 kHz) as the practical reliable ceiling — documented explicitly as a design trade-off.
- Range and reliability degrade with ambient noise, distance, and non-line-of-sight; mitigated but not eliminated by FEC + repetition.
- The acoustic ACK back-channel is best-effort in crowded/noisy rooms (many simultaneous ACK bursts can collide); UI is designed to make this limitation transparent to the organizer.

---

## 8. Tech Stack Summary

- Kotlin, Jetpack Compose (or XML, TBD by team preference) for UI
- `AudioRecord` / `AudioTrack` (android.media) — no third-party audio libs required, keeps APK small and avoids external hardware/network dependencies
- Reed-Solomon (small pure-Kotlin implementation) or Hamming(7,4) as fallback for FEC
- Unit tests for `AudioEncoder`/`AudioDecoder` round-trip on JVM (no emulator needed) to iterate fast before on-device testing
