# XDS Display · Cycling Head Unit

> Turn a spare Android phone into a handlebar **cycling display**: BLE power meter + GPS + on-board sensors → one glanceable dashboard.
> The relay that collects and forwards telemetry (another phone or a custom board) is *not* part of this repository.

Plain Java with hand-drawn native views. Only two dependencies: `appcompat` and `material` — no third-party chart/map library.

---

## Features

**Number cards** (tabular figures, so digits don't jump as values change)
- Power / speed / distance / average speed / cadence / ride time
- Aviation-HUD-style vertical speed tape beside the speed card (current speed pinned to the centre row)
- Power area chart + speed line chart, each auto-scaled to its own window
- Left/right pedal power balance strip along the bottom edge of the power card

**Map**
- OpenStreetMap tiles (Web Mercator) + live track + current position
- GPX route import and snapping, per-region offline tile download (degrades to a grid + track with no network)
- Drag / pinch-zoom / one-tap follow

**On-board sensing**
- Road-roughness bar: local variance of z-axis acceleration
- G-force ball (gravity-compensated attitude fusion)
- Wind-noise level bar: 200–1500 Hz microphone band energy, used as a "wind resistance" gauge

**Misc**
- Gear / ratio estimation (preloaded for the XDS AD350 2025: 1×12, 48T chainring + 11-48T cassette, 700c @ 2.146 m; change the tooth table and wheel circumference for your own bike)
- English / Chinese UI (AppCompat per-app locale, system language untouched)
- Hex-customisable colour theme, full-screen / windowed, portrait / landscape
- Settings → About links to this repository's issue tracker

## Data sources

| Link | Notes |
| --- | --- |
| BLE (primary) | Scans and connects to the standard Bluetooth Cycling Power service (`0x1818` / `0x1819`) advertised by the relay, subscribing to `0x2A63` Cycling Power Measurement. Auto-reconnects |
| BLE (fallback) | Some relay devices never answer CCCD writes on their GATT server, so a scan-only advertising link and a "this app acts as GATT server" link are also implemented |
| WiFi UDP | Relay opens a hotspot, this device joins it and receives telemetry over UDP |
| On-board sensors | GPS (speed / distance / track), accelerometer + gyroscope (roughness / G-force), microphone (wind noise) |

## Building

- JDK 17, Android SDK (`compileSdk 34` / `minSdk 26` / `targetSdk 34`), Android Gradle Plugin 8.4.0
- Command line:

  ```bash
  echo "sdk.dir=/path/to/Android/Sdk" > local.properties
  ./gradlew assembleDebug
  ```

  Output: `app/build/outputs/apk/debug/app-debug.apk`
- Or just open the repository root in Android Studio.

## Off-device self-checks

`verify/` contains PC-side tests for the **pure-algorithm** parts: those classes deliberately carry no Android dependency, so plain `javac` compiles and runs them — used to regression-test gear estimation, power parsing, attitude fusion, roughness and wind filtering without a phone:

```bash
cd verify/gear/_pc
javac GearRatio.java GearRatioTest.java && java GearRatioTest
```

Run outputs are build artefacts and are excluded via `.gitignore`.

## License

Source code is released under the **MIT** licence — see [LICENSE](LICENSE).
The bundled Orbitron Black digit font is **SIL OFL 1.1** — full text in [licenses/OFL-Orbitron.txt](licenses/OFL-Orbitron.txt).

## Feedback

Open an [issue](https://github.com/NoraNekowww/XdsDisplay/issues) for bugs or ideas; pull requests are welcome.
[中文说明](README.md)

## Disclaimer

Do not interact with the device while riding — mount it on the handlebar and leave it alone. The authors accept no liability for any accident.
