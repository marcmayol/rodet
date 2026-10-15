package com.marcmayol.rodet.flow

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/** OCR en el propio movil (modelo latino incluido en el APK). */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}

object Images {
    /** Lo bastante grande para leer letra pequena y lo bastante pequeno para no agotar memoria. */
    private const val MAX_SIDE = 2400

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees % 360 == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    /**
     * Recorta de la foto la zona que se veia dentro del marco guia. La vista
     * previa (FILL_CENTER) escala la imagen hasta cubrir la vista y recorta lo
     * que sobra; aqui se deshace esa cuenta.
     */
    fun cropToGuide(image: Bitmap, viewW: Float, viewH: Float, guide: GuideRect): Bitmap {
        val scale = max(viewW / image.width, viewH / image.height)
        val dx = (viewW - image.width * scale) / 2f
        val dy = (viewH - image.height * scale) / 2f
        fun ix(x: Float) = ((x - dx) / scale).roundToInt().coerceIn(0, image.width)
        fun iy(y: Float) = ((y - dy) / scale).roundToInt().coerceIn(0, image.height)
        val l = ix(guide.left * viewW)
        val t = iy(guide.top * viewH)
        val r = ix(guide.right * viewW)
        val b = iy(guide.bottom * viewH)
        if (r - l < 16 || b - t < 16) return image
        return Bitmap.createBitmap(image, l, t, r - l, b - t)
    }

    fun limit(bitmap: Bitmap): Bitmap {
        val side = max(bitmap.width, bitmap.height)
        if (side <= MAX_SIDE) return bitmap
        val f = MAX_SIDE.toFloat() / side
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).roundToInt(), (bitmap.height * f).roundToInt(), true)
    }

    /** Foto de la galeria; ImageDecoder ya aplica la orientacion EXIF. */
    fun load(resolver: ContentResolver, uri: Uri): Bitmap? = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val side = max(info.size.width, info.size.height)
                if (side > MAX_SIDE) d.setTargetSampleSize(side / MAX_SIDE + 1)
            }
        } else {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
        raw?.let(::limit)
    }.getOrNull()

    /**
     * Cuentagotas: media de un cuadrado alrededor del punto, para que un
     * brillo o una sombra puntual no decidan el color.
     */
    fun sampleHex(bitmap: Bitmap, x: Int, y: Int, radius: Int = 6): String {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (py in (y - radius)..(y + radius)) {
            for (px in (x - radius)..(x + radius)) {
                if (px !in 0 until bitmap.width || py !in 0 until bitmap.height) continue
                val c = bitmap.getPixel(px, py)
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
        }
        if (n == 0) return ""
        return "%02X%02X%02X".format(r / n, g / n, b / n)
    }
}

/** Marco guia en fracciones de la vista previa. */
data class GuideRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val DEFAULT = GuideRect(0.06f, 0.22f, 0.94f, 0.68f)
    }
}
