package com.metin.gozlukgemini

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Gözlükten gelen ham I420 kareyi JPEG'e çevirir. */
object FrameUtil {

    fun i420ToJpeg(buffer: ByteBuffer, width: Int, height: Int, quality: Int = 80): ByteArray? {
        val ySize = width * height
        val uvSize = ySize / 4
        val total = ySize + 2 * uvSize
        val dup = buffer.duplicate()
        dup.rewind()
        if (dup.remaining() < total) return null
        val i420 = ByteArray(total)
        dup.get(i420, 0, total)

        // I420 (Y, U, V) -> NV21 (Y, VU araya)
        val nv21 = ByteArray(total)
        System.arraycopy(i420, 0, nv21, 0, ySize)
        var o = ySize
        for (i in 0 until uvSize) {
            nv21[o++] = i420[ySize + uvSize + i] // V
            nv21[o++] = i420[ySize + i]          // U
        }
        val out = ByteArrayOutputStream()
        val ok = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality, out)
        return if (ok) out.toByteArray() else null
    }

    fun bitmapToJpeg(bmp: Bitmap, quality: Int = 80): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
