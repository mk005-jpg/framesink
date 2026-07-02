@file:Suppress("DEPRECATION")

package de.secpen.framesink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import de.secpen.framesink.databinding.ActivityMainBinding
import de.secpen.framesink.sim.FakeKycSdk
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

private typealias FrameCb = (nv21: ByteArray, w: Int, h: Int) -> Unit

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var source: FrameSource? = null
    private val main = Handler(Looper.getMainLooper())
    private val previewExec = Executors.newSingleThreadExecutor()

    // fps bookkeeping (read on the UI display loop)
    private var prevCount = 0L
    private var prevTs = 0L
    private var fps = 0.0
    @Volatile private var previewRenderInFlight = false
    private var lastRenderedFrameCount = -1L
    private var lastRenderAtMs = 0L

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startSelected() else b.stats.text = "CAMERA permission denied" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.modeCamera1.isChecked = true
        b.modeGroup.setOnCheckedChangeListener { _, _ -> restart() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startSelected()
        else permLauncher.launch(Manifest.permission.CAMERA)

        main.post(displayLoop)
    }

    override fun onDestroy() {
        main.removeCallbacks(displayLoop)
        source?.stop()
        previewExec.shutdownNow()
        super.onDestroy()
    }

    private fun restart() {
        source?.stop(); source = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startSelected()
    }

    private fun startSelected() {
        source?.stop()
        val cb: FrameCb = { nv21, w, h -> FakeKycSdk.sendFrameNV21(0L, nv21, w, h, 0, 0) }
        source = when {
            b.modeCamera2.isChecked -> Camera2Source(this, cb)
            b.modeCameraX.isChecked -> CameraXSource(this, cb)
            else -> Camera1Source(cb)
        }.also { runCatching { it.start() }.onFailure { e -> b.stats.text = "start failed: ${e.message}" } }
    }

    // ── UI refresh at ~15fps: show the frame FakeKycSdk received + stats ──
    private val displayLoop = object : Runnable {
        override fun run() {
            val snap = FakeKycSdk.snapshot()
            val w = FakeKycSdk.lastWidth
            val h = FakeKycSdk.lastHeight
            val count = FakeKycSdk.frameCount
            val now = System.currentTimeMillis()
            if (prevTs != 0L && now > prevTs) fps = (count - prevCount) * 1000.0 / (now - prevTs)
            prevCount = count; prevTs = now

            val mode = when { b.modeCamera2.isChecked -> "Camera2/ImageReader"
                b.modeCameraX.isChecked -> "CameraX/ImageAnalysis"; else -> "Camera1/onPreviewFrame" }
            if (snap != null && w > 0 && h > 0) {
                if (count != lastRenderedFrameCount && !previewRenderInFlight && now - lastRenderAtMs >= 180L) {
                    previewRenderInFlight = true
                    val frame = snap
                    val frameCount = count
                    runCatching {
                        previewExec.execute {
                            val bmp = nv21ToBitmap(frame, w, h)
                            main.post {
                                previewRenderInFlight = false
                                if (bmp != null && frameCount >= lastRenderedFrameCount) {
                                    lastRenderedFrameCount = frameCount
                                    lastRenderAtMs = System.currentTimeMillis()
                                    b.sinkView.setImageBitmapSafe(bmp)
                                }
                            }
                        }
                    }.onFailure {
                        previewRenderInFlight = false
                    }
                }
                b.stats.text = "%s | %dx%d | %d B | %.0f fps | frames=%d | csum=%08x"
                    .format(mode, w, h, snap.size, fps, count, checksum(snap))
            } else {
                b.stats.text = "$mode | waiting for frames…"
            }
            main.postDelayed(this, 66)
        }
    }
}

private fun ImageView.setImageBitmapSafe(bmp: Bitmap?) { if (bmp != null) setImageBitmap(bmp) }

private fun checksum(a: ByteArray): Int {
    var s = 0; val n = minOf(a.size, 4096)
    for (i in 0 until n) s = s * 31 + (a[i].toInt() and 0xff)
    return s
}

private fun nv21ToBitmap(nv21: ByteArray, w: Int, h: Int): Bitmap? = try {
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, out)
    val bytes = out.toByteArray()
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (e: Exception) { null }

/** YUV_420_888 (Camera2/CameraX) -> NV21, honoring row/pixel strides. */
private fun imageToNv21(image: Image): ByteArray {
    val w = image.width; val h = image.height
    val nv21 = ByteArray(w * h + 2 * (w / 2) * (h / 2))
    val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
    var pos = 0
    val yBuf = y.buffer; val yRow = y.rowStride; val yPix = y.pixelStride
    for (row in 0 until h) { var idx = row * yRow; for (col in 0 until w) { nv21[pos++] = yBuf.get(idx); idx += yPix } }
    val uBuf = u.buffer; val vBuf = v.buffer
    val uRow = u.rowStride; val uPix = u.pixelStride
    val vRow = v.rowStride; val vPix = v.pixelStride
    val cw = w / 2; val ch = h / 2
    for (row in 0 until ch) for (col in 0 until cw) {
        nv21[pos++] = vBuf.get(row * vRow + col * vPix)   // V
        nv21[pos++] = uBuf.get(row * uRow + col * uPix)   // U
    }
    return nv21
}

private interface FrameSource { fun start(); fun stop() }

// ── Camera1: setPreviewCallback -> onPreviewFrame(byte[] NV21) ──
private class Camera1Source(private val cb: FrameCb) : FrameSource {
    private var camera: Camera? = null
    private var dummy: SurfaceTexture? = null
    override fun start() {
        val id = frontOrFirst()
        val cam = Camera.open(id)
        camera = cam
        val p = cam.parameters
        val sz = p.supportedPreviewSizes.minByOrNull { Math.abs(it.width * it.height - 640 * 480) }!!
        p.setPreviewSize(sz.width, sz.height)
        p.previewFormat = ImageFormat.NV21
        cam.parameters = p
        dummy = SurfaceTexture(0).also { cam.setPreviewTexture(it) }
        cam.setPreviewCallback { data, _ -> if (data != null) cb(data, sz.width, sz.height) }
        cam.startPreview()
    }
    override fun stop() {
        camera?.runCatching { setPreviewCallback(null); stopPreview(); release() }
        camera = null; dummy?.release(); dummy = null
    }
    private fun frontOrFirst(): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i
        }
        return 0
    }
}

// ── Camera2: ImageReader (YUV_420_888) ──
private class Camera2Source(private val ctx: Context, private val cb: FrameCb) : FrameSource {
    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    override fun start() {
        val t = HandlerThread("cam2").also { it.start() }; thread = t
        val hh = Handler(t.looper); handler = hh
        val id = frontOrFirst()
        val cc = mgr.getCameraCharacteristics(id)
        val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val sz = sizes.minByOrNull { Math.abs(it.width * it.height - 640 * 480) } ?: Size(640, 480)
        val rd = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 3)
        reader = rd
        rd.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { cb(imageToNv21(img), img.width, img.height) } finally { img.close() }
        }, hh)
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                cam.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(rd.surface)
                        s.setRepeatingRequest(req.build(), null, hh)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, hh)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close() }
            override fun onError(cam: CameraDevice, error: Int) { cam.close() }
        }, hh)
    }
    override fun stop() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
        thread?.quitSafely(); thread = null; handler = null
    }
    private fun frontOrFirst(): String {
        for (id in mgr.cameraIdList) {
            val f = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (f == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return mgr.cameraIdList.first()
    }
}

// ── CameraX: ImageAnalysis.Analyzer.analyze(ImageProxy) ──
private class CameraXSource(private val activity: AppCompatActivity, private val cb: FrameCb) : FrameSource {
    private var provider: ProcessCameraProvider? = null
    private val exec = Executors.newSingleThreadExecutor()

    override fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val p = future.get(); provider = p
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(exec) { proxy ->
                try { proxy.image?.let { cb(imageToNv21(it), it.width, it.height) } } finally { proxy.close() }
            }
            val selector = if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            p.bindToLifecycle(activity, selector, analysis)
        }, ContextCompat.getMainExecutor(activity))
    }
    override fun stop() { runCatching { provider?.unbindAll() }; provider = null; exec.shutdown() }
}
