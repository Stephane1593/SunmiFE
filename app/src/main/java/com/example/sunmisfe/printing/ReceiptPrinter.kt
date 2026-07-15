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
    // Hardcoded as safety net so a wrong doc.width can never overflow.
    private const val PRINTER_CHARS = 32

    @SuppressLint("StaticFieldLeak")
    private var appCtx: Context? = null

    @Volatile
    private var service: SunmiPrinterService? = null

    private val cb = object : InnerPrinterCallback() {
        override fun onConnected(s: SunmiPrinterService) {
            service = s
            Log.i(TAG, "Sunmi printer connected")
            try {
                Log.i(TAG, "service version = ${s.printerVersion}")
            } catch (t: Throwable) {
                Log.w(TAG, "printerVersion unavailable", t)
            }
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
            val ok = InnerPrinterManager.getInstance().bindService(appCtx!!, cb)
            Log.i(TAG, "bindService returned $ok")
        } catch (t: Throwable) {
            Log.e(TAG, "bindService threw", t)
        }
    }

    // ─────────────────────────── Await / Helpers ─────────────────────────

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

    private fun logCallback(): InnerResultCallback {
        return object : InnerResultCallback() {
            override fun onRunResult(isSuccess: Boolean) {
                Log.d(TAG, "onRunResult: isSuccess=$isSuccess")
            }
            override fun onReturnString(result: String?) {
                Log.d(TAG, "onReturnString: result=$result")
            }
            override fun onRaiseException(code: Int, msg: String?) {
                Log.e(TAG, "onRaiseException: code=$code msg=$msg")
            }
            override fun onPrintResult(code: Int, msg: String?) {
                Log.d(TAG, "onPrintResult: code=$code msg=$msg")
            }
        }
    }

    /**
     * Commit the current printer buffer and wait for the real completion callback.
     * exitPrinterBufferWithCallback(commit=true) both commits AND exits the buffer.
     */
    private suspend fun commitBuffer(s: SunmiPrinterService, timeoutMs: Long = 10_000L): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                s.exitPrinterBufferWithCallback(true, object : InnerResultCallback() {
                    override fun onRunResult(isSuccess: Boolean) {
                        Log.d(TAG, "commit onRunResult=$isSuccess")
                        if (cont.isActive) cont.resume(isSuccess)
                    }
                    override fun onReturnString(r: String?) {}
                    override fun onRaiseException(code: Int, msg: String?) {
                        Log.e(TAG, "commit exception code=$code msg=$msg")
                        if (cont.isActive) cont.resume(false)
                    }
                    override fun onPrintResult(code: Int, msg: String?) {
                        Log.d(TAG, "commit onPrintResult code=$code msg=$msg")
                        if (cont.isActive) cont.resume(code == 0)
                    }
                })
            }
        } ?: false
    }

    /** Replace Unicode box-drawing / bullets with ASCII the printer can render. */
    private fun sanitize(s: String): String = s
        .replace('─', '-').replace('═', '=')
        .replace('│', '|').replace('•', '*')

    /** Ensure text terminates with a newline so it flushes immediately. */
    private fun ensureNewline(text: String): String =
        if (text.endsWith("\n")) text else text + "\n"

    // ─────────────────────────── DIAGNOSTIC TEST ───────────────────────────

    suspend fun testPrint() {
        Log.i(TAG, "testPrint: START")
        val s = awaitReady() ?: error("Printer service unavailable")
        Log.i(TAG, "version=${s.printerVersion} status=${s.updatePrinterState()}")

        val logCb = logCallback()
        s.printerInit(logCb)
        delay(200)

        s.enterPrinterBuffer(true)

        s.setAlignment(0, logCb)
        s.printText("STAGE 1: plain ASCII\n", logCb)
        s.printText("--------------------------------\n", logCb)

        s.setAlignment(1, logCb)
        s.setFontSize(32f, logCb)
        s.printText("STAGE 2 HEADER\n", logCb)
        s.setFontSize(24f, logCb)
        s.setAlignment(0, logCb)

        s.printText("STAGE 3: sanitized\n", logCb)
        s.printText(sanitize("Ligne accents: éàçùè\n"), logCb)
        s.lineWrap(4, logCb)

        val ok = commitBuffer(s)
        Log.i(TAG, "testPrint: commit result=$ok")

        if (!ok) error("Test print commit failed/timed out")
    }

    // ─────────────────────────── Public API ───────────────────────────

    /** Fetch, render, and print proforma. */
    suspend fun printProforma(orderId: String, copies: Int = 1): Result<Unit> {
        Log.i(TAG, "printProforma: START orderId=$orderId copies=$copies")

        val r = runCatching {
            val resp = SfeApiClient.api.getProformaReceiptJson(orderId, copies)
            Log.i(TAG, "printProforma: fetched JSON, ${resp.documents.size} document(s)")

            val text = sanitize(renderDocuments(resp.documents))
            Log.i(TAG, "printProforma: rendered text, ${text.length} chars")

            val s = awaitReady() ?: error("Printer service unavailable")
            val logCb = logCallback()

            s.printerInit(logCb)
            delay(200)

            s.enterPrinterBuffer(true)

            s.setAlignment(1, logCb)
            s.setFontSize(32f, logCb)
            s.printText("PROFORMA\n", logCb)
            s.setFontSize(24f, logCb)
            s.setAlignment(0, logCb)

            s.printText(ensureNewline(text), logCb)
            s.lineWrap(4, logCb)

            val ok = commitBuffer(s)
            Log.i(TAG, "printProforma: commit result=$ok")
            if (!ok) error("Proforma print commit failed/timed out")
        }

        if (r.isSuccess) {
            Log.i(TAG, "printProforma: SUCCESS")
            return Result.success(Unit)
        }

        val last = r.exceptionOrNull()
        Log.e(TAG, "printProforma: FAILED", last)
        return Result.failure(last ?: RuntimeException("Proforma indisponible"))
    }

    /** Fetch, render, and print fiscal receipt. Retries because 404 = not fiscalized yet. */
    suspend fun printFiscalWithRetry(
        orderId: String,
        copies: Int = 1,
        attempts: Int = 6
    ): Result<Unit> {
        var last: Throwable? = null
        repeat(attempts) { i ->
            Log.i(TAG, "printFiscalWithRetry: attempt ${i + 1}/$attempts orderId=$orderId")

            val r = runCatching {
                val resp = SfeApiClient.api.getFiscalReceiptJson(orderId, copies)
                Log.i(TAG, "printFiscalWithRetry: fetched JSON on attempt ${i + 1}")

                val text = sanitize(renderDocuments(resp.documents))
                Log.i(TAG, "printFiscalWithRetry: rendering complete (${text.length} chars)")

                val s = awaitReady() ?: error("Printer service unavailable")
                val logCb = logCallback()

                s.printerInit(logCb)
                delay(200)

                s.enterPrinterBuffer(true)

                s.setAlignment(1, logCb)
                s.setFontSize(32f, logCb)
                s.printText("REÇU FISCAL\n", logCb)
                s.setFontSize(24f, logCb)
                s.setAlignment(0, logCb)

                s.printText(ensureNewline(text), logCb)
                s.lineWrap(4, logCb)

                val ok = commitBuffer(s)
                Log.i(TAG, "printFiscalWithRetry: commit result=$ok on attempt ${i + 1}")
                if (!ok) error("Fiscal print commit failed/timed out")
            }

            if (r.isSuccess) {
                Log.i(TAG, "printFiscalWithRetry: SUCCESS on attempt ${i + 1}")
                return Result.success(Unit)
            }

            last = r.exceptionOrNull()
            Log.w(TAG, "printFiscalWithRetry: attempt ${i + 1} failed: ${last?.message}")
            delay(1000L * (i + 1))
        }

        Log.e(TAG, "printFiscalWithRetry: FAILED after $attempts attempts")
        return Result.failure(last ?: RuntimeException("Reçu fiscal indisponible"))
    }

    // ─────────────────────────── Rendering ───────────────────────────

    private fun renderDocuments(documents: List<ReceiptDocument>): String {
        Log.d(TAG, "renderDocuments: START, ${documents.size} document(s)")
        val sb = StringBuilder()
        documents.forEach { doc ->
            Log.d(TAG, "renderDocuments: document (width=${doc.width})")
            sb.append(renderDocument(doc))
        }
        val result = sb.toString()
        Log.i(TAG, "renderDocuments: RESULT (${result.length} chars)")
        return result
    }

    /** Render a single ReceiptDocument using PRINTER_CHARS (32) as hard limit. */
    private fun renderDocument(doc: ReceiptDocument): String {
        val sb = StringBuilder()
        val width = PRINTER_CHARS  // ✅ Always 32, ignoring doc.width

        doc.elements.forEach { elem ->
            val line = renderElement(elem, width)
            if (line.isNotEmpty()) {
                sb.append(line).append("\n")
            }
        }

        // ✅ Footer/PrintedAt/DUPLICATA come from elements — do NOT append again
        // (server now emits them as explicit ReceiptElement)

        return sb.toString()
    }

    private fun renderElement(elem: ReceiptElement, width: Int): String {
        return when (elem.type) {
            ReceiptElement.TYPE_TEXT -> alignText(elem.text ?: "", elem.align, width)
            ReceiptElement.TYPE_ROW -> rowText(elem.left ?: "", elem.right ?: "", width)
            ReceiptElement.TYPE_THREE_COL ->
                threeColText(elem.left ?: "", elem.middle ?: "", elem.right ?: "", width)
            ReceiptElement.TYPE_DASH_LINE -> "-".repeat(width)
            ReceiptElement.TYPE_DOUBLE_LINE -> "=".repeat(width)
            ReceiptElement.TYPE_FEED -> "\n".repeat((elem.feedLines - 1).coerceAtLeast(0))
            ReceiptElement.TYPE_QR_CODE -> elem.text?.let { "[QR: $it]" } ?: ""
            ReceiptElement.TYPE_LOGO -> elem.text?.let { "[LOGO: $it]" } ?: ""
            else -> {
                Log.w(TAG, "renderElement: unknown type=${elem.type}")
                ""
            }
        }
    }

    private fun alignText(text: String, align: String, width: Int): String {
        val trimmed = text.take(width)
        return when (align) {
            ReceiptElement.ALIGN_CENTER -> centerText(trimmed, width)
            ReceiptElement.ALIGN_RIGHT -> trimmed.padStart(width)
            else -> trimmed.padEnd(width)
        }
    }

    private fun centerText(text: String, width: Int): String {
        val trimmed = text.take(width)
        val spaces = (width - trimmed.length) / 2
        return " ".repeat(spaces.coerceAtLeast(0)) + trimmed
    }

    /** Right value keeps priority (it's the amount); left fills the gap.
     *  Guarantees left + gap + right == width, never wraps to second line. */
    private fun rowText(left: String, right: String, width: Int): String {
        val r = right.take(width)
        val space = width - r.length
        val l = if (left.length > space - 1)
            left.take((space - 1).coerceAtLeast(0))
        else
            left
        val gap = (width - l.length - r.length).coerceAtLeast(1)
        return l + " ".repeat(gap) + r
    }

    private fun threeColText(left: String, mid: String, right: String, width: Int): String {
        val colWidth = width / 3
        val l = left.take(colWidth).padEnd(colWidth)
        val m = mid.take(colWidth).padEnd(colWidth)
        val r = right.take(width - 2 * colWidth).padStart(width - 2 * colWidth)
        return l + m + r
    }

    // ─────────────────────────── Cleanup ─────────────────────────

    fun unbind() {
        val ctx = appCtx ?: return
        try {
            InnerPrinterManager.getInstance().unBindService(ctx, cb)
            Log.i(TAG, "unbindService ok")
        } catch (t: Throwable) {
            Log.w(TAG, "unbindService threw", t)
        }
        service = null
        appCtx = null
    }
}