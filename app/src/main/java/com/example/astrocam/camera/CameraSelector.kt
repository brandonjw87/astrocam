package com.example.astrocam.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.util.SizeF
import kotlin.math.abs

/** Binned (default, ~12 MP on Pixel) vs. full-sensor (~50 MP) RAW readout. */
enum class Resolution { BINNED, FULL }

/** Everything the app needs to know about the chosen rear camera. */
data class AstroCameraInfo(
  val cameraId: String,
  val characteristics: CameraCharacteristics,
  val sensorSizeMm: SizeF,
  val fNumber: Float,
  val focalLengthMm: Float,
  val exposureRangeNs: Range<Long>,
  val isoRange: Range<Int>,
  val maxAnalogIso: Int,
  val rawSize: Size,
  val rawMinFrameDurationNs: Long,
  /** Full-sensor RAW size (ULTRA_HIGH_RESOLUTION_SENSOR), null if the HAL doesn't expose it. */
  val maxResRawSize: Size?,
  val maxResRawMinFrameDurationNs: Long,
  /** Set when the full-res mode is only available on a physical sub-camera of a logical camera. */
  val maxResPhysicalId: String?,
  val maxResCharacteristics: CameraCharacteristics?,
  val previewSize: Size,
  val sensorOrientation: Int,
  /** Diopters. 0 = infinity. */
  val minFocusDiopters: Float,
  val focusCalibrated: Boolean,
  val hasOisControl: Boolean,
  val supportsLensShadingMap: Boolean,
  val score: Double,
) {
  val supportsFullRes: Boolean get() = maxResRawSize != null

  fun rawSizeFor(r: Resolution): Size = if (r == Resolution.FULL && maxResRawSize != null) maxResRawSize else rawSize

  fun minFrameDurationFor(r: Resolution): Long =
    if (r == Resolution.FULL && maxResRawSize != null) maxResRawMinFrameDurationNs else rawMinFrameDurationNs

  /** Characteristics that describe the RAW stream for [r] (used for DNG + colour processing). */
  fun charsFor(r: Resolution): CameraCharacteristics =
    if (r == Resolution.FULL && maxResCharacteristics != null) maxResCharacteristics else characteristics

  fun physicalIdFor(r: Resolution): String? = if (r == Resolution.FULL && maxResRawSize != null) maxResPhysicalId else null

  fun megapixels(r: Resolution): Double = rawSizeFor(r).let { it.width.toDouble() * it.height / 1e6 }

  val description: String
    get() =
      "Camera $cameraId · ${"%.1f".format(sensorSizeMm.width)}×${"%.1f".format(sensorSizeMm.height)} mm · " +
        "f/${"%.2f".format(fNumber)} · ${"%.2f".format(focalLengthMm)} mm · RAW ${"%.1f".format(megapixels(Resolution.BINNED))} MP" +
        (maxResRawSize?.let { " / ${"%.1f".format(megapixels(Resolution.FULL))} MP" } ?: "")
}

object CameraSelector {

  /**
   * Picks the best rear camera for astrophotography.
   *
   * On a Pixel 10 Pro XL this is the 50 MP main (wide) camera: it has by far the largest sensor
   * and the brightest aperture. Rather than hard-coding an ID we score every back-facing camera
   * that supports manual sensor control + RAW by light-gathering power (sensor area / f-number²),
   * so the app keeps doing the right thing across firmware updates.
   */
  fun selectBestBackCamera(manager: CameraManager): AstroCameraInfo? {
    val candidates = mutableListOf<AstroCameraInfo>()
    for (id in manager.cameraIdList) {
      val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
      if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue

      val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
      val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
      val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps
      if (!manual || !raw) continue

      val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
      val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: continue
      if (rawSizes.isEmpty()) continue
      val rawSize = rawSizes.maxBy { it.width.toLong() * it.height }
      val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(1f, 1f)

      // Full-resolution (remosaiced quad-Bayer) RAW, if exposed to third-party apps.
      var maxRes = findMaxRes(c, caps, rawSize)?.let { MaxRes(it.first, it.second, null, null) }
      if (maxRes == null && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
        // Pixels are logical multi-cameras; the 50 MP mode may only be advertised by the physical main sensor.
        maxRes = findPhysicalMaxRes(manager, c, sensor, rawSize)
      }

      val apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
      val fNumber = apertures?.minOrNull() ?: 2.0f
      val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
      val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: continue
      val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: continue
      val maxAnalog = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: isoRange.upper

      val previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture::class.java), rawSize)
      val oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
      val shadingModes =
        c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES) ?: IntArray(0)

      // Light gathering ~ sensor area / N²; small bonus for longer max single exposure.
      val area = sensor.width.toDouble() * sensor.height
      val score = area / (fNumber.toDouble() * fNumber) * (1.0 + expRange.upper / 1e10)

      candidates +=
        AstroCameraInfo(
          cameraId = id,
          characteristics = c,
          sensorSizeMm = sensor,
          fNumber = fNumber,
          focalLengthMm = focal,
          exposureRangeNs = expRange,
          isoRange = isoRange,
          maxAnalogIso = maxAnalog,
          rawSize = rawSize,
          rawMinFrameDurationNs = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize),
          maxResRawSize = maxRes?.size,
          maxResRawMinFrameDurationNs = maxRes?.minFrameNs ?: 0L,
          maxResPhysicalId = maxRes?.physicalId,
          maxResCharacteristics = maxRes?.chars,
          previewSize = previewSize,
          sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
          minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
          focusCalibrated =
            c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) !=
              CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED,
          hasOisControl = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF in oisModes,
          supportsLensShadingMap = CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON in shadingModes,
          score = score,
        )
    }
    return candidates.maxByOrNull { it.score }
  }

  private class MaxRes(val size: Size, val minFrameNs: Long, val physicalId: String?, val chars: CameraCharacteristics?)

  /** Returns (size, minFrameDuration) of the maximum-resolution RAW stream, if larger than the default one. */
  private fun findMaxRes(c: CameraCharacteristics, caps: Set<Int>, defaultRaw: Size): Pair<Size, Long>? {
    if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR !in caps) return null
    if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return null
    val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) ?: return null
    val big = maxMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } ?: return null
    if (big.width.toLong() * big.height <= defaultRaw.width.toLong() * defaultRaw.height) return null
    val minFrame = runCatching { maxMap.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, big) }.getOrDefault(0L)
    return big to minFrame
  }

  private fun findPhysicalMaxRes(manager: CameraManager, logical: CameraCharacteristics, logicalSensor: SizeF, defaultRaw: Size): MaxRes? {
    val logicalArea = logicalSensor.width * logicalSensor.height
    var best: MaxRes? = null
    for (pid in logical.physicalCameraIds) {
      val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
      val pcaps = pc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: continue
      val ps = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
      // Only the main (largest) sensor is interesting; ignore the ultra-wide / tele.
      if (ps.width * ps.height < logicalArea * 0.8f) continue
      val (size, minFrame) = findMaxRes(pc, pcaps, defaultRaw) ?: continue
      if (best == null || size.width.toLong() * size.height > best.size.width.toLong() * best.size.height) {
        best = MaxRes(size, minFrame, pid, pc)
      }
    }
    return best
  }

  /** Preview stream matching the RAW aspect ratio, no larger than 1920 px wide. */
  private fun choosePreviewSize(sizes: Array<Size>?, rawSize: Size): Size {
    if (sizes.isNullOrEmpty()) return Size(1440, 1080)
    val target = rawSize.width.toFloat() / rawSize.height
    val matching =
      sizes.filter { abs(it.width.toFloat() / it.height - target) < 0.02f && it.width <= 1920 }
    return (matching.ifEmpty { sizes.filter { it.width <= 1920 } }.ifEmpty { sizes.toList() })
      .maxBy { it.width.toLong() * it.height }
  }
}
