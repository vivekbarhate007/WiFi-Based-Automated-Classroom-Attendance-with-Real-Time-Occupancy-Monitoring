package com.attendance.router

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Thin wrapper around ZXing's QRCodeWriter. Produces a square monochrome
 * Bitmap that we can drop straight into an ImageView.
 */
object QrCodeGenerator {

    /**
     * Encodes [content] as a QR code bitmap of [sizePx] × [sizePx] pixels.
     * Returns null if ZXing fails (e.g. content too large for the chosen level).
     */
    fun encodeAsBitmap(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank()) return null

        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )

        return try {
            val matrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
            )
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Exception) {
            null
        }
    }
}
