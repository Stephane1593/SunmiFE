package com.example.sunmisfe.printing

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.example.sunmisfe.network.SfeApiClient
import com.example.sunmisfe.network.ReceiptDocument
import com.example.sunmisfe.network.ReceiptElement
import kotlin.coroutines.resume

object ReceiptPrinter {

    private const val TAG = "ReceiptPrinter"

    // Physical printer width. 58mm head = 32 chars at font size 24.
    private const val PRINTER_CHARS = 32

    @SuppressLint("StaticFieldLeak")
    private var appCtx: Context? = null

    @Volatile
    private var service: SunmiPrinterService? = null

    private val cb = object : InnerPrinterCallback() {
        override fun onConnected(s: SunmiPrinterService) {
            service = s
            Log.i(TAG, "Sunmi printer connected, version = ${s.printerVersion}")
        }
        override fun onDisconnected() {
            service = null
            Log.w(TAG, "Sunmi printer disconnected")
        }
    }

    fun bind(context: Context) {
        if (appCtx != null) return
        appCtx = context.applicationContext
        try {
            InnerPrinterManager.getInstance().bindService(appCtx!!, cb)
        } catch (t: Throwable) {
            Log.e(TAG, "bindService threw", t)
        }
    }

    private suspend fun awaitReady(timeoutMs: Long = 5000): SunmiPrinterService? {
        service?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            var attempts = 0
            while (service == null && attempts < 50) {
                delay(100)
                attempts++
            }
            service
        }
    }

    private fun logCallback() = object : InnerResultCallback() {
        override fun onRunResult(isSuccess: Boolean) {}
        override fun onReturnString(result: String?) {}
        override fun onRaiseException(code: Int, msg: String?) {
            Log.e(TAG, "Printer Exception: code=$code msg=$msg")
        }
        override fun onPrintResult(code: Int, msg: String?) {}
    }

    private suspend fun commitBuffer(s: SunmiPrinterService, timeoutMs: Long = 10_000L): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                s.exitPrinterBufferWithCallback(true, object : InnerResultCallback() {
                    override fun onRunResult(isSuccess: Boolean) {
                        if (cont.isActive) cont.resume(isSuccess)
                    }
                    override fun onReturnString(r: String?) {}
                    override fun onRaiseException(code: Int, msg: String?) {
                        if (cont.isActive) cont.resume(false)
                    }
                    override fun onPrintResult(code: Int, msg: String?) {
                        if (cont.isActive) cont.resume(code == 0)
                    }
                })
            }
        } ?: false
    }

    private fun sanitize(s: String): String = s
        .replace('─', '-').replace('═', '=')
        .replace('│', '|').replace('•', '*')

    private fun ensureNewline(text: String): String =
        if (text.endsWith("\n")) text else "$text\n"

    // ─────────────────────────── Public API ───────────────────────────

    suspend fun printProforma(
        orderId: String,
        copies: Int = 1,
        paymentMethod: String? = null
    ): Result<Unit> {
        val r = runCatching {
            val resp = SfeApiClient.api.getProformaReceiptJson(orderId, copies, paymentMethod)
            val s = awaitReady() ?: error("Printer service unavailable")
            val logCb = logCallback()

            s.printerInit(logCb)
            delay(200)
            s.enterPrinterBuffer(true)

            printDocuments(s, resp.documents, logCb)

            val ok = commitBuffer(s)
            if (!ok) error("Print commit failed")
        }

        return if (r.isSuccess) Result.success(Unit)
        else Result.failure(r.exceptionOrNull() ?: RuntimeException("Erreur d'impression"))
    }

    suspend fun printFiscalWithRetry(
        orderId: String,
        copies: Int = 1,
        paymentMethod: String? = null,
        attempts: Int = 6
    ): Result<Unit> {
        var last: Throwable? = null
        repeat(attempts) { i ->
            val r = runCatching {
                val resp = SfeApiClient.api.getFiscalReceiptJson(orderId, copies, paymentMethod)
                val s = awaitReady() ?: error("Printer service unavailable")
                val logCb = logCallback()

                s.printerInit(logCb)
                delay(200)
                s.enterPrinterBuffer(true)

                printDocuments(s, resp.documents, logCb)

                val ok = commitBuffer(s)
                if (!ok) error("Print commit failed")
            }

            if (r.isSuccess) return Result.success(Unit)

            last = r.exceptionOrNull()
            delay(1000L * (i + 1))
        }

        return Result.failure(last ?: RuntimeException("Reçu fiscal indisponible"))
    }

    suspend fun printFromQr(documents: List<ReceiptDocument>): Result<Unit> {
        val s = awaitReady() ?: return Result.failure(Exception("Printer unavailable"))
        val logCb = logCallback()

        s.printerInit(logCb)
        delay(200)
        s.enterPrinterBuffer(true)

        printDocuments(s, documents, logCb)

        val ok = commitBuffer(s)
        return if (ok) Result.success(Unit) else Result.failure(Exception("Print failed"))
    }

    suspend fun testPrint() {
        val s = awaitReady() ?: error("Printer service unavailable")
        val logCb = logCallback()
        s.printerInit(logCb)
        delay(200)
        s.enterPrinterBuffer(true)
        s.setAlignment(1, logCb)
        s.setFontSize(32f, logCb)
        s.printText("TEST D'IMPRESSION\n", logCb)
        s.setFontSize(24f, logCb)
        s.setAlignment(0, logCb)
        s.printText("Le terminal fonctionne correctement.\n", logCb)
        s.lineWrap(4, logCb)
        commitBuffer(s)
    }

    // ─────────────────────────── Core Rendering ───────────────────────────

    private fun applyFormatting(s: SunmiPrinterService, elem: ReceiptElement, logCb: InnerResultCallback) {
        // Alignment
        val alignInt = when (elem.align) {
            ReceiptElement.ALIGN_CENTER -> 1
            ReceiptElement.ALIGN_RIGHT -> 2
            else -> 0
        }
        s.setAlignment(alignInt, logCb)

        // Bold ESC/POS command
        if (elem.bold) {
            s.sendRAWData(byteArrayOf(0x1B, 0x45, 0x01), logCb)
        } else {
            s.sendRAWData(byteArrayOf(0x1B, 0x45, 0x00), logCb)
        }

        // Size
        val size = when {
            elem.doubleSize -> 48f
            elem.doubleHeight -> 30f // 👈 Reduced from 36f down to 30f for a subtler emphasis
            else -> 24f
        }
        s.setFontSize(size, logCb)
    }

    private suspend fun printDocuments(
        s: SunmiPrinterService,
        documents: List<ReceiptDocument>,
        logCb: InnerResultCallback
    ) {
        documents.forEachIndexed { index, doc ->

            doc.elements.forEach { elem ->
                applyFormatting(s, elem, logCb)

                when (elem.type) {
                    ReceiptElement.TYPE_QR_CODE -> {
                        elem.text?.let {
                            s.setAlignment(1, logCb)
                            s.printQRCode(it, 6, 3, logCb)
                            s.printText("\n", logCb)
                        }
                    }
                    ReceiptElement.TYPE_FEED -> {
                        s.lineWrap(elem.feedLines, logCb)
                    }
                    ReceiptElement.TYPE_LOGO -> {
                        // Implement Base64 Bitmap decoding if needed
                    }
                    else -> {
                        // 💡 FIX 1: If font is larger, the physical line holds fewer characters.
                        val effectiveWidth = when {
                            elem.doubleSize -> PRINTER_CHARS / 2       // ~16 chars
                            elem.doubleHeight -> 26                    // 👈 Increased from 24 to 26 characters
                            else -> PRINTER_CHARS                      // 32 chars
                        }

                        val text = renderElementText(elem, effectiveWidth)
                        if (text.isNotEmpty()) {
                            s.printText(ensureNewline(sanitize(text)), logCb)
                        }
                    }
                }

                // 💡 FIX 3: Reset font and bold back to normal AFTER printing the element
                s.setFontSize(24f, logCb)
                s.sendRAWData(byteArrayOf(0x1B, 0x45, 0x00), logCb)
                s.setAlignment(0, logCb)
            }

            // Cut between copies
            if (index < documents.size - 1) {
                s.cutPaper(logCb)
            }
        }
    }

    private fun renderElementText(elem: ReceiptElement, width: Int): String {
        return when (elem.type) {
            ReceiptElement.TYPE_TEXT -> elem.text ?: ""
            ReceiptElement.TYPE_ROW -> rowText(elem.left ?: "", elem.right ?: "", width)
            ReceiptElement.TYPE_THREE_COL -> threeColText(elem.left ?: "", elem.middle ?: "", elem.right ?: "", width)
            ReceiptElement.TYPE_DASH_LINE -> "-".repeat(width)
            ReceiptElement.TYPE_DOUBLE_LINE -> "=".repeat(width)
            else -> ""
        }
    }

    // ─────────────────────────── String Helpers ───────────────────────────

    private fun rowText(left: String, right: String, width: Int): String {
        val l = left.trim()
        val r = right.trim()

        // 💡 FIX 2: If the total length exceeds the line width,
        // we force a space between them and let the printer's native text wrap handle it naturally.
        if (l.length + r.length + 1 > width) {
            return "$l $r"
        }

        val space = width - r.length
        val paddedLeft = if (l.length > space - 1) l.take((space - 1).coerceAtLeast(0)) else l

        val gap = (width - paddedLeft.length - r.length).coerceAtLeast(1)
        return paddedLeft + " ".repeat(gap) + r
    }

    private fun threeColText(left: String, mid: String, right: String, width: Int): String {
        val colWidth = width / 3
        val l = left.take(colWidth).padEnd(colWidth)
        val m = mid.take(colWidth).padEnd(colWidth)
        val rWidth = (width - (colWidth * 2)).coerceAtLeast(0)
        val r = right.take(rWidth).padStart(rWidth)
        return l + m + r
    }

    fun unbind() {
        val ctx = appCtx ?: return
        try {
            InnerPrinterManager.getInstance().unBindService(ctx, cb)
        } catch (t: Throwable) {
            Log.w(TAG, "unbindService threw", t)
        }
        service = null
        appCtx = null
    }
}