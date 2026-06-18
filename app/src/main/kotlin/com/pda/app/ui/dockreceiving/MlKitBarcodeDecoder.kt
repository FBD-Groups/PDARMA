package com.pda.app.ui.dockreceiving

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Bundled ML Kit 条码解码（离线、不依赖 Google Play 服务）。对**全分辨率原图**解码，
 * 解出全部条码后用 [pickTrackingBarcode] 挑出运单号。任何失败都返回 null，绝不抛进拍照流程。
 */
@Singleton
class MlKitBarcodeDecoder @Inject constructor(
    @ApplicationContext private val context: Context
) : BarcodeDecoder {

    private companion object {
        const val TAG = "PDA/MlKitBarcodeDecoder"
    }

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_DATA_MATRIX
                )
                .build()
        )
    }

    override suspend fun decodeTracking(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val values = suspendCancellableCoroutine { cont ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes -> cont.resume(barcodes.mapNotNull { it.rawValue }) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "decode failed: ${e.message}")
                        cont.resume(emptyList())
                    }
            }
            val picked = pickTrackingBarcode(values)
            Log.i(TAG, "decoded ${values.size} barcode(s): $values -> tracking=${picked ?: "none"}")
            picked
        } catch (e: Exception) {
            Log.w(TAG, "decodeTracking: ${e.message}", e)
            null
        }
    }
}
