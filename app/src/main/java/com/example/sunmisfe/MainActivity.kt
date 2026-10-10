package com.example.sunmisfe

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.example.sunmisfe.data.TerminalConfig
import com.example.sunmisfe.network.SfeApiClient
import com.example.sunmisfe.printing.ReceiptPrinter
import com.example.sunmisfe.ui.theme.PosApp
import com.example.sunmisfe.util.OfflineQrDecoder

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate")

        // 💡 Keep the POS terminal screen turned on permanently while the app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize configurations and hardware
        TerminalConfig.init(applicationContext)
        SfeApiClient.init(applicationContext)
        ReceiptPrinter.bind(applicationContext)

        setContent {
            MaterialTheme { PosApp() }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "MainActivity onDestroy")
        ReceiptPrinter.unbind()
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)

        val scanned = intent?.getStringExtra("data") // depends on device scanner intent
        if (!scanned.isNullOrBlank()) {
            handleQr(scanned)
        }
    }

    private fun handleQr(token: String) {
        val payload = OfflineQrDecoder.decode(token)

        if (payload == null) {
            Log.e("QR", "Invalid or tampered QR")
            return
        }

        Log.i("QR", "Order: ${payload.orderId}, amount=${payload.amount}")

        // 👉 Note: ScannedQrStore is handled directly in PosApp's rememberQrScanner for now,
        // but if hardware intents bypass Compose, you can inject them into ScannedQrStore here.
    }
}