package com.example.astrocam.processing

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult

/** Per-capture metadata needed to turn Bayer data into colour pixels. */
class RawMeta(
  val black: FloatArray, // per CFA position, index = row*2 + col
  val white: Float,
  val positionChannel: IntArray, // per CFA position -> 0=R, 1=Ge, 2=Go, 3=B
  val wbGains: FloatArray, // R, Ge, Go, B
  val ccm: FloatArray, // 3x3 row-major, camera RGB -> linear sRGB
  val shading: FloatArray?,
  val shadingCols: Int,
  val shadingRows: Int,
) {
  val meanBlack: Float get() = black.average().toFloat()

  companion object {
    fun from(chars: CameraCharacteristics, result: CaptureResult): RawMeta {
      val dynBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
      val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
      val black =
        FloatArray(4) { idx ->
          dynBlack?.getOrNull(idx) ?: pattern?.getOffsetForIndex(idx and 1, idx shr 1)?.toFloat() ?: 64f
        }
      val white =
        (result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: 1023)
          .toFloat()

      val cfa =
        chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
          ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
      val posCh =
        when (cfa) {
          CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 3, 2)
          CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 3, 0, 2)
          CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(3, 1, 2, 0)
          else -> intArrayOf(0, 1, 2, 3) // RGGB
        }

      val g = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
      val wb =
        if (g != null) floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue)
        else floatArrayOf(2.0f, 1f, 1f, 1.6f)

      val t = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
      val ccm =
        if (t != null) FloatArray(9) { k -> t.getElement(k % 3, k / 3).toFloat() }
        else floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

      val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
      var shading: FloatArray? = null
      var cols = 0
      var rows = 0
      if (map != null && map.columnCount > 1 && map.rowCount > 1) {
        cols = map.columnCount
        rows = map.rowCount
        shading = FloatArray(map.gainFactorCount).also { map.copyGainFactors(it, 0) }
      }
      return RawMeta(black, white, posCh, wb, ccm, shading, cols, rows)
    }
  }
}
