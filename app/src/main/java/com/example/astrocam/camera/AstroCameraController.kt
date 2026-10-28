package com.example.astrocam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.astrocam.processing.RawStacker
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class PreviewSettings(
  val focusDiopters: Float = 0f,
  val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
  /** Use a long manual exposure for the viewfinder so you can actually frame stars. */
  val nightBoost: Boolean = true,
)

data class CaptureSettings(
  val totalExposureNs: Long,
  val subExposureNs: Long,
  val iso: Int,
  val focusDiopters: Float,
  val awbMode: Int,
)

/** A running stack. Lives on the RAW thread; read by the UI after it completes. */
class CaptureJob(val settings: CaptureSettings, val stacker: RawStacker) {
  val targetFrames = AtomicInteger(max(1, ceil(settings.totalExposureNs.toDouble() / settings.subExposureNs).toInt()))
  @Volatile var accepting = true
  @Volatile var firstResult: TotalCaptureResult? = null
  @Volatile var actualExposureNs: Long = settings.subExposureNs
  @Volatile var actualIso: Int = settings.iso
  @Volatile var droppedFrames = 0
  val framesDone: Int get() = stacker.frameCount
}

interface CameraListener {
  fun onCameraReady()
  fun onCameraError(message: String)
  fun onCaptureProgress(job: CaptureJob)
  fun onCaptureComplete(job: CaptureJob)
}

/**
 * Camera2 controller: viewfinder + a repeating fully-manual RAW request for the stack.
 * All camera calls run on a dedicated thread; RAW accumulation runs on another.
 */
class AstroCameraController(context: Context, val info: AstroCameraInfo, private val listener: CameraListener) {
  private val manager = context.getSystemService(CameraManager::class.java)
  private val camThread = HandlerThread("astro-camera").apply { start() }
  private val camHandler = Handler(camThread.looper)
  private val camExecutor = Executor { camHandler.post(it) }
  private val rawThread = HandlerThread("astro-raw").apply { start() }
  private val rawHandler = Handler(rawThread.looper)

  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var rawReader: ImageReader? = null
  private var previewSurface: Surface? = null
  @Volatile private var job: CaptureJob? = null
  @Volatile var previewSettings = PreviewSettings()
    private set

  val isOpen: Boolean get() = device != null

  @SuppressLint("MissingPermission")
  fun open(texture: SurfaceTexture) =
    camHandler.post {
      if (device != null) return@post
      try {
        texture.setDefaultBufferSize(info.previewSize.width, info.previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(texture)
        rawReader?.close()
        rawReader = ImageReader.newInstance(info.rawSize.width, info.rawSize.height, ImageFormat.RAW_SENSOR, 4)
        manager.openCamera(info.cameraId, camExecutor, deviceCallback)
      } catch (t: Throwable) {
        Log.e(TAG, "open failed", t)
        listener.onCameraError("Could not open camera: ${t.message}")
      }
    }

  fun close() =
    camHandler.post {
      job?.accepting = false
      job = null
      runCatching { session?.close() }
      session = null
      runCatching { device?.close() }
      device = null
      runCatching { rawReader?.close() }
      rawReader = null
      previewSurface?.release()
      previewSurface = null
    }

  fun release() {
    close()
    camThread.quitSafely()
    rawThread.quitSafely()
  }

  fun updatePreview(settings: PreviewSettings) {
    previewSettings = settings
    camHandler.post { if (job == null) startPreview() }
  }

  private val deviceCallback =
    object : CameraDevice.StateCallback() {
      override fun onOpened(camera: CameraDevice) {
        device = camera
        createSession(camera)
      }

      override fun onDisconnected(camera: CameraDevice) {
        camera.close()
        device = null
        session = null
        failRunningJob("Camera was disconnected (another app took it?)")
      }

      override fun onError(camera: CameraDevice, error: Int) {
        camera.close()
        device = null
        session = null
        failRunningJob("Camera error $error")
        listener.onCameraError("Camera error $error")
      }
    }

  private fun failRunningJob(msg: String) {
    job?.let {
      it.accepting = false
      job = null
      listener.onCameraError(msg)
    }
  }

  private fun createSession(camera: CameraDevice) {
    val preview = previewSurface ?: return
    val raw = rawReader ?: return
    val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(raw.surface))
    val config =
      SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        outputs,
        camExecutor,
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(s: CameraCaptureSession) {
            session = s
            startPreview()
            listener.onCameraReady()
          }

          override fun onConfigureFailed(s: CameraCaptureSession) {
            listener.onCameraError("Camera session configuration failed")
          }
        },
      )
    camera.createCaptureSession(config)
  }

  private fun CaptureRequest.Builder.applyCommon(focus: Float, awb: Int) {
    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
    set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, max(0f, info.minFocusDiopters)))
    set(CaptureRequest.CONTROL_AWB_MODE, awb)
    set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f) // 1x = main sensor on Pixel's logical camera
    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    if (info.hasOisControl) {
      // Phone is on the ground: OIS only adds drift during long exposures.
      set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
    }
  }

  private fun startPreview() {
    val d = device ?: return
    val s = session ?: return
    val surface = previewSurface ?: return
    val p = previewSettings
    try {
      val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      b.addTarget(surface)
      b.applyCommon(p.focusDiopters, p.awbMode)
      if (p.nightBoost) {
        val exp = min(info.exposureRangeNs.upper, 250_000_000L).coerceAtLeast(info.exposureRangeNs.lower)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, exp)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, info.isoRange.clamp(6400))
      } else {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
      }
      s.setRepeatingRequest(b.build(), null, camHandler)
    } catch (t: Throwable) {
      Log.e(TAG, "preview failed", t)
    }
  }

  /** Starts the stack. Returns false if the camera isn't ready. */
  fun startCapture(newJob: CaptureJob): Boolean {
    if (session == null || device == null || rawReader == null) return false
    camHandler.post { beginCapture(newJob) }
    return true
  }

  private fun beginCapture(j: CaptureJob) {
    val d = device ?: return listener.onCameraError("Camera not open")
    val s = session ?: return listener.onCameraError("Camera not ready")
    val reader = rawReader ?: return listener.onCameraError("RAW stream unavailable")
    val cs = j.settings
    job = j

    reader.setOnImageAvailableListener({ r ->
      val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
      try {
        if (!j.accepting) return@setOnImageAvailableListener
        j.stacker.add(img)
        listener.onCaptureProgress(j)
        if (j.stacker.frameCount >= j.targetFrames.get()) {
          j.accepting = false
          camHandler.post {
            runCatching { session?.stopRepeating() }
            if (job === j) job = null
            startPreview()
          }
          listener.onCaptureComplete(j)
        }
      } catch (t: Throwable) {
        Log.e(TAG, "stack failed", t)
        j.accepting = false
        listener.onCameraError("Stacking failed: ${t.message}")
      } finally {
        img.close()
      }
    }, rawHandler)

    try {
      val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
      b.addTarget(reader.surface)
      b.applyCommon(cs.focusDiopters, cs.awbMode)
      b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
      b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, cs.subExposureNs)
      b.set(CaptureRequest.SENSOR_SENSITIVITY, cs.iso)
      b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(cs.subExposureNs, info.rawMinFrameDurationNs))
      b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
      b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
      b.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
      if (info.supportsLensShadingMap) {
        // Embedded in the DNG + used for the JPEG so corners aren't dark after stretching.
        b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
      }
      s.stopRepeating()
      s.setRepeatingRequest(b.build(), captureCallback(j), camHandler)
    } catch (t: Throwable) {
      Log.e(TAG, "capture failed", t)
      j.accepting = false
      job = null
      listener.onCameraError("Could not start exposure: ${t.message}")
      startPreview()
    }
  }

  private fun captureCallback(j: CaptureJob) =
    object : CameraCaptureSession.CaptureCallback() {
      override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
        if (j.firstResult == null) {
          j.firstResult = result
          // The HAL may silently clamp exposure; recompute frames so total integration time is honoured.
          val actual = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME) ?: j.settings.subExposureNs
          j.actualExposureNs = actual
          j.actualIso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: j.settings.iso
          if (actual > 0) {
            j.targetFrames.set(max(1, ceil(j.settings.totalExposureNs.toDouble() / actual).toInt()))
          }
        }
      }

      override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
        j.droppedFrames++
      }
    }

  /** Cancels a running stack and returns to the viewfinder. */
  fun abortCapture() =
    camHandler.post {
      job?.accepting = false
      job = null
      runCatching {
        session?.stopRepeating()
        session?.abortCaptures()
      }
      startPreview()
    }

  companion object {
    private const val TAG = "AstroCam"
  }
}
