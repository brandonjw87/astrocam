package com.example.astrocam.ui

import android.app.Application
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.astrocam.audio.CountdownAnnouncer
import com.example.astrocam.camera.AstroCameraController
import com.example.astrocam.camera.AstroCameraInfo
import com.example.astrocam.camera.CameraListener
import com.example.astrocam.camera.CameraSelector
import com.example.astrocam.camera.CaptureJob
import com.example.astrocam.camera.CaptureSettings
import com.example.astrocam.camera.PreviewSettings
import com.example.astrocam.processing.MediaSaver
import com.example.astrocam.processing.RawMeta
import com.example.astrocam.processing.RawStacker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OutputMode(val label: String) {
  BOTH("Both"),
  LONG_EXPOSURE("Long exposure"),
  STAR_TRAILS("Star trails"),
}

enum class WhiteBalance(val label: String, val mode: Int) {
  AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
  DAYLIGHT("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
  FLUORESCENT("~4000K", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
  INCANDESCENT("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
}

data class Settings(
  val totalSeconds: Int = 300,
  val subExposureNs: Long = 1_000_000_000L,
  val iso: Int = 1600,
  val focusDiopters: Float = 0f,
  val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
  val output: OutputMode = OutputMode.BOTH,
  val nightBoost: Boolean = true,
)

sealed interface Phase {
  data object Idle : Phase

  data class Countdown(val secondsLeft: Int) : Phase

  data class Capturing(
    val frames: Int,
    val targetFrames: Int,
    val elapsedMs: Long,
    val actualExposureNs: Long,
    val actualIso: Int,
  ) : Phase

  data class Processing(val message: String) : Phase

  data class Done(val lines: List<String>) : Phase

  data class Failed(val message: String) : Phase
}

data class UiState(
  val info: AstroCameraInfo? = null,
  val hasPermission: Boolean = false,
  val cameraReady: Boolean = false,
  val settings: Settings = Settings(),
  val phase: Phase = Phase.Idle,
  val subExposureOptionsNs: List<Long> = emptyList(),
  val isoOptions: List<Int> = emptyList(),
  val error: String? = null,
)

class AstroViewModel(app: Application) : AndroidViewModel(app), CameraListener {
  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private val announcer = CountdownAnnouncer(app)
  private var controller: AstroCameraController? = null
  private var texture: SurfaceTexture? = null
  private var foreground = false
  private var sequence: Job? = null
  private var captureStartMs = 0L

  init {
    val info = CameraSelector.selectBestBackCamera(app.getSystemService(CameraManager::class.java))
    if (info == null) {
      _state.update { it.copy(error = "No rear camera with manual RAW control found.") }
    } else {
      controller = AstroCameraController(app, info, this)
      val subOptions =
        (listOf(info.exposureRangeNs.upper) +
            listOf(30.0, 20.0, 15.0, 10.0, 8.0, 4.0, 2.0, 1.0, 0.5, 0.25).map { (it * 1e9).toLong() })
          .filter { it in info.exposureRangeNs }
          .distinct()
          .sortedDescending()
      val isoOptions =
        listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800).filter { it in info.isoRange }
      // Default: longest sub-exposure up to 16 s (keeps per-frame trailing small), ISO 1600.
      val defaultSub = subOptions.firstOrNull { it <= 16_000_000_000L } ?: subOptions.last()
      val defaultIso = isoOptions.minByOrNull { kotlin.math.abs(it - 1600) } ?: info.isoRange.clamp(1600)
      _state.update {
        it.copy(
          info = info,
          subExposureOptionsNs = subOptions,
          isoOptions = isoOptions,
          settings = it.settings.copy(subExposureNs = defaultSub, iso = defaultIso),
        )
      }
    }
  }

  // ---------- lifecycle wiring ----------

  fun setPermission(granted: Boolean) {
    _state.update { it.copy(hasPermission = granted) }
    maybeOpen()
  }

  fun onSurfaceAvailable(st: SurfaceTexture) {
    texture = st
    maybeOpen()
  }

  fun onSurfaceDestroyed() {
    texture = null
    closeCamera()
  }

  fun setForeground(fg: Boolean) {
    foreground = fg
    if (fg) maybeOpen() else closeCamera()
  }

  private fun maybeOpen() {
    val c = controller ?: return
    val st = texture ?: return
    if (!foreground || !_state.value.hasPermission || c.isOpen) return
    c.open(st)
  }

  private fun closeCamera() {
    val phase = _state.value.phase
    if (phase is Phase.Countdown || phase is Phase.Capturing) {
      sequence?.cancel()
      announcer.cancelled()
      announcer.endLoud()
      _state.update { it.copy(phase = Phase.Failed("Exposure stopped: the app left the screen. Keep AstroCam open.")) }
    }
    controller?.close()
    _state.update { it.copy(cameraReady = false) }
  }

  // ---------- settings ----------

  fun updateSettings(transform: (Settings) -> Settings) {
    _state.update { it.copy(settings = transform(it.settings)) }
    val s = _state.value.settings
    controller?.updatePreview(PreviewSettings(s.focusDiopters, s.whiteBalance.mode, s.nightBoost))
  }

  // ---------- capture sequence ----------

  fun start() {
    val c = controller ?: return
    if (!_state.value.cameraReady || sequence?.isActive == true) return
    val s = _state.value.settings
    val info = c.info

    sequence =
      viewModelScope.launch {
        // Loud 10-second countdown, drift-free.
        announcer.beginLoud()
        val t0 = SystemClock.elapsedRealtime()
        for (n in COUNTDOWN_SECONDS downTo 1) {
          _state.update { it.copy(phase = Phase.Countdown(n)) }
          announcer.tick(n)
          val next = t0 + (COUNTDOWN_SECONDS - n + 1) * 1000L
          delay(next - SystemClock.elapsedRealtime())
        }
        _state.update { it.copy(phase = Phase.Countdown(0)) }
        announcer.go()
        // Let the speaker tone and any hand-shake settle before the shutter opens.
        delay(SETTLE_MS)
        announcer.endLoud()

        val stacker =
          try {
            withContext(Dispatchers.Default) { RawStacker(info.rawSize.width, info.rawSize.height) }
          } catch (oom: OutOfMemoryError) {
            fail("Not enough memory for the RAW stack buffers.")
            return@launch
          }
        val job =
          CaptureJob(
            CaptureSettings(
              totalExposureNs = s.totalSeconds * 1_000_000_000L,
              subExposureNs = s.subExposureNs,
              iso = s.iso,
              focusDiopters = s.focusDiopters,
              awbMode = s.whiteBalance.mode,
            ),
            stacker,
          )
        captureStartMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(phase = capturingPhase(job)) }
        if (!c.startCapture(job)) {
          fail("Camera not ready.")
          return@launch
        }
        // Tick the elapsed clock while frames come in.
        while (isActive) {
          delay(250)
          val p = _state.value.phase
          if (p is Phase.Capturing) {
            _state.update { st ->
              val cur = st.phase
              if (cur is Phase.Capturing) st.copy(phase = cur.copy(elapsedMs = SystemClock.elapsedRealtime() - captureStartMs))
              else st
            }
          } else break
        }
      }
  }

  fun cancel() {
    sequence?.cancel()
    announcer.cancelled()
    announcer.endLoud()
    controller?.abortCapture()
    _state.update { it.copy(phase = Phase.Idle) }
  }

  fun dismissResult() {
    _state.update { it.copy(phase = Phase.Idle) }
  }

  private fun capturingPhase(job: CaptureJob) =
    Phase.Capturing(
      frames = job.framesDone,
      targetFrames = job.targetFrames.get(),
      elapsedMs = SystemClock.elapsedRealtime() - captureStartMs,
      actualExposureNs = job.actualExposureNs,
      actualIso = job.actualIso,
    )

  private fun fail(msg: String) {
    announcer.failed()
    _state.update { it.copy(phase = Phase.Failed(msg)) }
  }

  // ---------- CameraListener (called from camera / RAW threads) ----------

  override fun onCameraReady() {
    _state.update { it.copy(cameraReady = true) }
  }

  override fun onCameraError(message: String) {
    val phase = _state.value.phase
    if (phase is Phase.Capturing || phase is Phase.Countdown) {
      sequence?.cancel()
      controller?.abortCapture()
      fail(message)
    } else {
      _state.update { it.copy(error = message) }
    }
  }

  override fun onCaptureProgress(job: CaptureJob) {
    _state.update { st -> if (st.phase is Phase.Capturing) st.copy(phase = capturingPhase(job)) else st }
  }

  override fun onCaptureComplete(job: CaptureJob) {
    val info = controller?.info ?: return
    val s = _state.value.settings
    _state.update { it.copy(phase = Phase.Processing("Stacking ${job.framesDone} frames…")) }
    announcer.beginLoud()
    announcer.done()

    viewModelScope.launch(Dispatchers.Default) {
      try {
        // The metadata result normally arrives before the image, but wait briefly just in case.
        var waited = 0
        while (job.firstResult == null && waited < 3000) {
          delay(50)
          waited += 50
        }
        val result = job.firstResult ?: error("No capture metadata received")
        val ctx = getApplication<Application>()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val n = job.framesDone
        val expSec = job.actualExposureNs / 1e9
        val totalSec = n * expSec
        val desc =
          "AstroCam stack: $n x ${"%.2f".format(expSec)} s @ ISO ${job.actualIso} = ${"%.0f".format(totalSec)} s total"
        val meta = RawMeta.from(info.characteristics, result)
        val saved = mutableListOf<String>()
        val w = info.rawSize.width
        val h = info.rawSize.height

        if (s.output != OutputMode.STAR_TRAILS) {
          setProcessing("Saving long-exposure DNG…")
          MediaSaver.saveDng(ctx, "AstroCam_${stamp}_longexposure", info.characteristics, result, w, h,
            job.stacker.averageToBuffer(), info.sensorOrientation, desc)
          saved += "AstroCam_${stamp}_longexposure.dng"
          setProcessing("Rendering long-exposure JPEG…")
          val bmp = job.stacker.render(RawStacker.Source.AVERAGE, meta, info.sensorOrientation)
          MediaSaver.saveJpeg(ctx, "AstroCam_${stamp}_longexposure", bmp)
          bmp.recycle()
          saved += "AstroCam_${stamp}_longexposure.jpg"
        }
        if (s.output != OutputMode.LONG_EXPOSURE) {
          setProcessing("Saving star-trails DNG…")
          MediaSaver.saveDng(ctx, "AstroCam_${stamp}_startrails", info.characteristics, result, w, h,
            job.stacker.maxToBuffer(), info.sensorOrientation, "$desc (lighten blend)")
          saved += "AstroCam_${stamp}_startrails.dng"
          setProcessing("Rendering star-trails JPEG…")
          val bmp = job.stacker.render(RawStacker.Source.MAX, meta, info.sensorOrientation)
          MediaSaver.saveJpeg(ctx, "AstroCam_${stamp}_startrails", bmp)
          bmp.recycle()
          saved += "AstroCam_${stamp}_startrails.jpg"
        }

        val lines =
          listOf(
            "$n frames × ${formatExposure(job.actualExposureNs)} @ ISO ${job.actualIso}",
            "Total integration: ${formatDuration(totalSec.toLong())}" +
              if (job.droppedFrames > 0) " (${job.droppedFrames} dropped)" else "",
            "Saved to Pictures/AstroCam:",
          ) + saved.map { "  • $it" }
        _state.update { it.copy(phase = Phase.Done(lines)) }
      } catch (t: Throwable) {
        fail("Saving failed: ${t.message}")
      } finally {
        delay(4000) // let the "complete" announcement finish at full volume
        announcer.endLoud()
      }
    }
  }

  private fun setProcessing(msg: String) = _state.update { it.copy(phase = Phase.Processing(msg)) }

  override fun onCleared() {
    controller?.release()
    announcer.shutdown()
  }

  companion object {
    const val COUNTDOWN_SECONDS = 10
    private const val SETTLE_MS = 1500L

    fun formatExposure(ns: Long): String {
      val s = ns / 1e9
      return if (s >= 1.0) "${"%.1f".format(s).removeSuffix(".0")} s" else "1/${"%.0f".format(1.0 / s)} s"
    }

    fun formatDuration(sec: Long): String =
      if (sec >= 60) "${sec / 60} min${if (sec % 60 != 0L) " ${sec % 60} s" else ""}" else "$sec s"
  }
}
