package com.example.astrocam.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.OutputStream

/** Writes results into Pictures/AstroCam via MediaStore (no storage permission needed). */
object MediaSaver {
  private const val RELATIVE_DIR = "Pictures/AstroCam"

  fun saveDng(
    context: Context,
    name: String,
    chars: CameraCharacteristics,
    result: CaptureResult,
    width: Int,
    height: Int,
    pixels: java.nio.ByteBuffer,
    sensorOrientation: Int,
    description: String,
  ): Uri =
    insert(context, "$name.dng", "image/x-adobe-dng") { out ->
      DngCreator(chars, result).use { dng ->
        dng.setOrientation(exifOrientation(sensorOrientation))
        dng.setDescription(description)
        dng.writeByteBuffer(out, Size(width, height), pixels, 0)
      }
    }

  fun saveJpeg(context: Context, name: String, bitmap: Bitmap): Uri =
    insert(context, "$name.jpg", "image/jpeg") { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }

  private fun insert(context: Context, displayName: String, mime: String, write: (OutputStream) -> Unit): Uri {
    val resolver = context.contentResolver
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed for $displayName")
    try {
      resolver.openOutputStream(uri)!!.use { write(it) }
      values.clear()
      values.put(MediaStore.MediaColumns.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
    } catch (t: Throwable) {
      resolver.delete(uri, null, null)
      throw t
    }
    return uri
  }

  private fun exifOrientation(deg: Int): Int =
    when ((deg % 360 + 360) % 360) {
      90 -> ExifInterface.ORIENTATION_ROTATE_90
      180 -> ExifInterface.ORIENTATION_ROTATE_180
      270 -> ExifInterface.ORIENTATION_ROTATE_270
      else -> ExifInterface.ORIENTATION_NORMAL
    }
}
