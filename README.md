# FrameSink

A tiny Android **camera frame-reception test harness**. It grabs frames the same ways real
apps (incl. KYC SDKs) do, feeds each frame through a Jumio-shaped **mock liveness SDK**, and
displays **the frame the "SDK" actually receives** — so you can validate camera-injection
tooling against a controllable target instead of a live KYC app.

> For authorized security testing / development only.

## Why

Different apps receive camera frames differently, and camera-injection hooks attach at
different layers. FrameSink lets you switch the reception method at runtime and *see* what
the consumer gets, so you can confirm an injector actually replaces the frame for that path:

| Mode | API | Injector layer it exercises |
|------|-----|------------------------------|
| **Camera1** | `Camera.setPreviewCallback` → `onPreviewFrame(byte[] NV21)` | legacy preview-callback hooks |
| **Camera2** | `ImageReader` (YUV_420_888) | `ImageReader.acquire*` hooks |
| **CameraX** | `ImageAnalysis.Analyzer.analyze(ImageProxy)` | CameraX/`ImageReader` hooks (Jumio SDK ≥ 4.7) |

Every received frame is converted to NV21 and passed to
`de.secpen.framesink.sim.FakeKycSdk.sendFrameNV21(long, byte[], int, int, int, int)` — the
**same signature as Jumio's `DaClient2.sendFrameNV21`** — and the on-screen image shows
exactly the bytes that method received. So:

- Hook the **camera API** (e.g. VcamX `vcam_frida.js`) → the displayed frame changes.
- Hook the **SDK method** (`kyc_frida.js`, which also targets `FakeKycSdk.sendFrameNV21`) →
  the displayed frame changes.

The overlay shows mode, resolution, frame size, fps, and a rolling checksum so injection is
obvious even with a static image.

## Build & run

```sh
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk   # or the produced apk name
```
Requires JDK 17 (`JAVA_HOME`), Android SDK (copy `local.properties.example` → `local.properties`
and set `sdk.dir`), and the CAMERA permission (requested at runtime).

## Test with the injectors

```sh
# camera-level (VcamX)
frida -U -f de.secpen.framesink -l <vcamx>/src/main/assets/vcam_frida.js --no-pause
# SDK-method + YUV_420_888 level (KYC agent)
frida -U -f de.secpen.framesink -l <vcamx>/src/main/assets/kyc_frida.js --no-pause
```
Switch modes in the app; the displayed "SDK frame" should show the injected content.

## License
MIT — see [LICENSE](LICENSE).
