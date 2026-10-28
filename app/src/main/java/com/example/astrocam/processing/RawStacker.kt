package com.example.astrocam.processing

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Accumulates RAW_SENSOR (16-bit Bayer) frames. All big buffers are *direct* (native memory), so a
 * 50 MP stack doesn't hit the Java heap limit.
 *
 *  - SUM   -> averaged "long exposure" (noise drops with sqrt(frames))
 *  - MAX   -> "lighten" blend = star trails; also used to drop the brightest frame per pixel
 *             (removes planes / satellites from the long exposure)
 *  - COMET -> decaying maximum = comet-style trails that fade out behind each star
 */
class RawStacker(
  val width: Int,
  val height: Int,
  keepMax: Boolean,
  keepComet: Boolean,
  /** Black level the comet trail decays towards. */
  private val cometBlack: Int = 64,
  /** Per-frame retention of the comet trail (0..1). */
  cometRetention: Float = 0.94f,
) {
  enum class Kind { AVERAGE, AVERAGE_CLEAN, MAX, COMET }

  private val pixelCount = width * height
  private val sum: IntBuffer = direct(pixelCount * 4).asIntBuffer()
  private val maxv: ShortBuffer? = if (keepMax) direct(pixelCount * 2).asShortBuffer() else null
  private val comet: ShortBuffer? = if (keepComet) direct(pixelCount * 2).asShortBuffer() else null
  private val cometK = (cometRetention.coerceIn(0f, 1f) * 256).toInt()

  private val rowIn = ShortArray(width)
  private val rowSum = IntArray(width)
  private val rowMax = ShortArray(width)
  private val rowComet = ShortArray(width)

  @Volatile var frameCount = 0
    private set

  fun supports(kind: Kind) =
    when (kind) {
      Kind.AVERAGE -> true
      Kind.AVERAGE_CLEAN, Kind.MAX -> maxv != null
      Kind.COMET -> comet != null
    }

  /** Called on the RAW processing thread for every frame. */
  fun add(image: Image) {
    val plane = image.planes[0]
    addPlane(plane.buffer, plane.rowStride, min(width, image.width), min(height, image.height))
  }

  fun addPlane(buffer: ByteBuffer, rowStrideBytes: Int, w: Int, h: Int) {
    val src = buffer.duplicate()
    src.position(0)
    src.order(ByteOrder.nativeOrder())
    val sv = src.asShortBuffer()
    val stride = rowStrideBytes / 2
    val first = frameCount == 0
    val mx = maxv
    val cm = comet
    for (y in 0 until h) {
      sv.position(y * stride)
      sv.get(rowIn, 0, w)
      val base = y * width

      sum.position(base)
      sum.get(rowSum, 0, w)
      for (x in 0 until w) rowSum[x] += rowIn[x].toInt() and 0xFFFF
      sum.position(base)
      sum.put(rowSum, 0, w)

      if (mx != null) {
        mx.position(base)
        mx.get(rowMax, 0, w)
        for (x in 0 until w) {
          if ((rowIn[x].toInt() and 0xFFFF) > (rowMax[x].toInt() and 0xFFFF)) rowMax[x] = rowIn[x]
        }
        mx.position(base)
        mx.put(rowMax, 0, w)
      }

      if (cm != null) {
        if (first) {
          cm.position(base)
          cm.put(rowIn, 0, w)
        } else {
          cm.position(base)
          cm.get(rowComet, 0, w)
          val b = cometBlack
          val k = cometK
          for (x in 0 until w) {
            val t = rowComet[x].toInt() and 0xFFFF
            val decayed = b + (((t - b).coerceAtLeast(0) * k) shr 8)
            val v = rowIn[x].toInt() and 0xFFFF
            rowComet[x] = max(decayed, v).toShort()
          }
          cm.position(base)
          cm.put(rowComet, 0, w)
        }
      }
    }
    frameCount++
  }

  /** Writes the requested blend as 16-bit Bayer into [out] (a direct buffer of width*height*2 bytes). */
  fun compose(kind: Kind, out: ByteBuffer) {
    val n = max(1, frameCount)
    val dst = out.duplicate().order(ByteOrder.nativeOrder())
    dst.position(0)
    val ov = dst.asShortBuffer()
    val tmp = ShortArray(width)
    val effective = if (supports(kind)) kind else Kind.AVERAGE
    for (y in 0 until height) {
      val base = y * width
      when (effective) {
        Kind.AVERAGE -> {
          sum.position(base)
          sum.get(rowSum, 0, width)
          val half = n / 2
          for (x in 0 until width) tmp[x] = ((rowSum[x] + half) / n).coerceAtMost(65535).toShort()
        }
        Kind.AVERAGE_CLEAN -> {
          sum.position(base)
          sum.get(rowSum, 0, width)
          val mx = maxv!!
          mx.position(base)
          mx.get(rowMax, 0, width)
          if (n >= 3) {
            val d = n - 1
            val half = d / 2
            for (x in 0 until width) {
              tmp[x] = ((rowSum[x] - (rowMax[x].toInt() and 0xFFFF) + half) / d).coerceIn(0, 65535).toShort()
            }
          } else {
            val half = n / 2
            for (x in 0 until width) tmp[x] = ((rowSum[x] + half) / n).coerceAtMost(65535).toShort()
          }
        }
        Kind.MAX -> {
          val mx = maxv!!
          mx.position(base)
          mx.get(tmp, 0, width)
        }
        Kind.COMET -> {
          val cm = comet!!
          cm.position(base)
          cm.get(tmp, 0, width)
        }
      }
      ov.position(base)
      ov.put(tmp, 0, width)
    }
  }

  fun newBayerBuffer(): ByteBuffer = direct(pixelCount * 2)

  companion object {
    fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    /** Estimated native memory for a stack, in bytes. */
    fun estimateBytes(width: Int, height: Int, keepMax: Boolean, keepComet: Boolean): Long {
      val px = width.toLong() * height
      return px * (4 + (if (keepMax) 2 else 0) + (if (keepComet) 2 else 0) + 2 /* output */ + 4 /* bitmap */)
    }
  }
}
