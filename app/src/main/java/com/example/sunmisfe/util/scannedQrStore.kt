package com.example.sunmisfe.util

import androidx.compose.runtime.mutableStateListOf
import com.example.sunmisfe.network.OrderDto

data class ScannedOrderMetadata(
    val isOffline: Boolean = true,  // QR-scanned orders are always offline
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * In-memory store for orders that arrived via QR scan (offline path).
 * Keyed by orderId so PaymentScreen can retrieve the embedded receipt payload.
 */
object ScannedQrStore {

    val scannedOrders = mutableStateListOf<OrderDto>()
    private val payloads = HashMap<String, OrderQrPayload>()
    private val metadata = HashMap<String, ScannedOrderMetadata>()

    fun add(payload: OrderQrPayload) {
        payloads[payload.orderId] = payload
        metadata[payload.orderId] = ScannedOrderMetadata(isOffline = true)

        val dto = OrderDto(
            orderId = payload.orderId,
            label = if (payload.kind == "Fiscal") "Facture (hors-ligne)" else "Proforma (hors-ligne)",
            amount = payload.amount,
            currency = payload.currency
        )

        val idx = scannedOrders.indexOfFirst { it.orderId == dto.orderId }
        if (idx >= 0) scannedOrders[idx] = dto else scannedOrders.add(0, dto)
    }

    fun get(orderId: String): OrderQrPayload? = payloads[orderId]

    fun isScanned(orderId: String): Boolean = payloads.containsKey(orderId)

    fun isOffline(orderId: String): Boolean = metadata[orderId]?.isOffline ?: false

    fun remove(orderId: String) {
        payloads.remove(orderId)
        metadata.remove(orderId)
        scannedOrders.removeAll { it.orderId == orderId }
    }

    fun clear() {
        payloads.clear()
        metadata.clear()
        scannedOrders.clear()
    }

    fun getAll(): Map<String, OrderQrPayload> = payloads.toMap()

    fun size(): Int = scannedOrders.size
}