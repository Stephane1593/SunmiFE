package com.example.sunmisfe.util

import android.util.Base64
import com.example.sunmisfe.network.ReceiptDocument
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
data class OrderQrPayload(
    val version: Int,
    val idempotencyKey: String,
    val orderId: String,
    val amount: Double,
    val currency: String,
    val caisseId: String,
    val issuedUnix: Long,
    val kind: String,
    val documents: List<ReceiptDocument>?,
    val fiscalCode: String?,
    val fiscalQr: String?
)

object OfflineQrDecoder {

    private val gson = Gson()

    // ⚠️ MUST match backend pairingSecret
    private val secret = "ikwookQrcodebaby".toByteArray()

    fun decode(token: String): OrderQrPayload? {
        val parts = token.split(".")
        if (parts.size != 2) return null

        val bodyB64 = parts[0]
        val sigB64 = parts[1]

        val expectedSig = hmac(bodyB64)
        val actualSig = base64UrlDecode(sigB64)

        if (!expectedSig.contentEquals(actualSig)) return null

        return try {
            // Decompress the body
            val compressed = base64UrlDecode(bodyB64)
            val decompressed = decompress(compressed)
            val json = String(decompressed, Charsets.UTF_8)
            gson.fromJson(json, OrderQrPayload::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun decompress(compressed: ByteArray): ByteArray {
        val inputStream = ByteArrayInputStream(compressed)
        val gzipInputStream = GZIPInputStream(inputStream)
        return gzipInputStream.readBytes()
    }
    private fun hmac(body: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.US_ASCII))
    }

    private fun base64UrlDecode(input: String): ByteArray {
        var s = input.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        return Base64.decode(s, Base64.DEFAULT)
    }
}