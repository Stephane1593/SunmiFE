package com.example.sunmisfe.util

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Returns a lambda that, when invoked, opens the ZXing camera scanner.
 * Works on Sunmi V2 Pro (rear autofocus camera).
 */
@Composable
fun rememberQrScanner(onResult: (String?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        onResult(result.contents) // null if user cancelled
    }
    return remember(launcher) {
        {
            val opts = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scannez le QR de la commande")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            launcher.launch(opts)
        }
    }
}