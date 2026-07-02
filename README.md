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


The overlay shows mode, resolution, frame size, fps, and a rolling checksum so injection is
obvious even with a static image.

## Build & run

```sh
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk   # or the produced apk name
```
Requires JDK 17 (`JAVA_HOME`), Android SDK (copy `local.properties.example` → `local.properties`
and set `sdk.dir`), and the CAMERA permission (requested at runtime).


Switch modes in the app; the displayed "SDK frame" should show the injected content.

## License
MIT — see [LICENSE](LICENSE).
