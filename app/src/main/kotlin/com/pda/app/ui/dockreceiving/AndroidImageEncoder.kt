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

    override suspend fun compress(file: File): CompressedImage = withContext(Dispatchers.IO) {
        // 1) bounds-only decode to read source dimensions
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val srcW = bounds.outWidth.coerceAtLeast(1)
        val srcH = bounds.outHeight.coerceAtLeast(1)

        // 2) downsample on decode
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(srcW, srcH, MAX_EDGE)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: throw IllegalStateException("无法读取照片文件")

        // 2.5) 套用 EXIF 方向。BitmapFactory 不会读 EXIF，相机拍出的 JPEG 多带"旋转 90°"
        // 标记；不转正会把躺倒的图发给 AI，运单号识别率骤降。转正后丢弃原图（已含在 oriented 内）。
        val oriented = applyExifOrientation(decoded, file.absolutePath)

        // 3) precise scale to MAX_EDGE longest edge（用转正后的尺寸，90/270 时宽高已对调）
        val (targetW, targetH) = scaledSize(oriented.width, oriented.height, MAX_EDGE)
        val scaled = if (targetW != oriented.width || targetH != oriented.height) {
            Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        } else oriented

        // 4) JPEG encode
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== oriented) scaled.recycle()
        oriented.recycle()

        val bytes = out.toByteArray()
        CompressedImage(bytes = bytes, base64 = Base64.getEncoder().encodeToString(bytes))
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImageEncoderModule {
    @Binds
    @Singleton
    abstract fun bindImageEncoder(impl: AndroidImageEncoder): ImageEncoder
}
