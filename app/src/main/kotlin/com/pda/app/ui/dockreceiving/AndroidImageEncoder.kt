package com.pda.app.ui.dockreceiving

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidImageEncoder @Inject constructor() : ImageEncoder {

    /**
     * 存档：直接用相机 JPEG 原文件，不缩边、不裁切（预览改 FIT_CENTER 后 Capture 也是完整 FOV）。
     * 对齐网页「按原始分辨率保存」；仅当体积过大时再轻量压缩。
     */
    override suspend fun prepareForUpload(file: File): ByteArray = withContext(Dispatchers.IO) {
        val original = file.readBytes()
        if (original.size <= MAX_UPLOAD_BYTES) return@withContext original
        // 超 3MB：转正后按质量阶梯 / 必要时缩到 2560（与网页兜底一致），仍不裁切。
        encodeFullFrame(file, softMaxEdge = UPLOAD_SOFT_MAX_EDGE)
    }

    override suspend fun compress(file: File): CompressedImage = withContext(Dispatchers.IO) {
        val bytes = encodeFullFrame(file, softMaxEdge = MAX_EDGE, quality = JPEG_QUALITY)
        CompressedImage(bytes = bytes, base64 = Base64.getEncoder().encodeToString(bytes))
    }

    private fun encodeFullFrame(
        file: File,
        softMaxEdge: Int,
        quality: Int = UPLOAD_JPEG_QUALITY
    ): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val srcW = bounds.outWidth.coerceAtLeast(1)
        val srcH = bounds.outHeight.coerceAtLeast(1)

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(srcW, srcH, softMaxEdge)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: throw IllegalStateException("无法读取照片文件")

        val oriented = applyExifOrientation(decoded, file.absolutePath)
        val (targetW, targetH) = scaledSize(oriented.width, oriented.height, softMaxEdge)
        val scaled = if (targetW != oriented.width || targetH != oriented.height) {
            Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        } else oriented

        fun toJpeg(q: Int): ByteArray {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
            return out.toByteArray()
        }

        var bytes = toJpeg(quality)
        if (softMaxEdge > MAX_EDGE && bytes.size > MAX_UPLOAD_BYTES) {
            for (q in listOf(85, 75, 65)) {
                bytes = toJpeg(q)
                if (bytes.size <= MAX_UPLOAD_BYTES) break
            }
        }

        if (scaled !== oriented) scaled.recycle()
        oriented.recycle()
        return bytes
    }

    /**
     * 按文件的 EXIF 方向把 [bitmap] 转正。返回转正后的新 bitmap（并回收原 [bitmap]），
     * 若无需旋转/读取失败则原样返回。
     */
    private fun applyExifOrientation(bitmap: Bitmap, path: String): Bitmap {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    companion object {
        private const val MAX_UPLOAD_BYTES = 3 * 1024 * 1024
        private const val UPLOAD_SOFT_MAX_EDGE = 2560
        private const val UPLOAD_JPEG_QUALITY = 95
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImageEncoderModule {
    @Binds
    @Singleton
    abstract fun bindImageEncoder(impl: AndroidImageEncoder): ImageEncoder
}
