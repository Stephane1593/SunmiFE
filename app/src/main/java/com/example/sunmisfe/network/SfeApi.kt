package com.example.sunmisfe.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

data class OrderDto(
    val orderId: String,
    val label: String,
    val amount: Double,
    val currency: String
)

data class OrderQrResponse(
    val token: String,
    val kind: String,          // "Fiscal" | "Provisional"
    val order: OrderDto
)

interface SfeApi {

    @GET("orders")
    suspend fun getOrders(): List<OrderDto>

    /**
     * Remove/cancel an order from the pending store.
     * Returns 204 No Content on success, 404 if not found.
     */
    @DELETE("orders/{id}")
    suspend fun cancelOrder(@Path("id") id: String): Response<Unit>

    // Alias for clarity (optional, for backward compat)
    suspend fun clearOrder(id: String) = cancelOrder(id)

    // ── Receipts (ESC/POS bytes, streamed back as application/vnd.escpos) ──

    @Streaming
    @GET("receipts/proforma/{id}")
    suspend fun getProformaReceipt(@Path("id") id: String): Response<ResponseBody>

    @Streaming
    @GET("receipts/fiscal/{id}")
    suspend fun getFiscalReceipt(@Path("id") id: String): Response<ResponseBody>

    // ── Receipts as JSON (for Sunmi rendering) ──

    @GET("receipts/json/proforma/{id}")
    suspend fun getProformaReceiptJson(
        @Path("id") id: String,
        @Query("copies") copies: Int = 1,
        @Query("paymentMethod") paymentMethod: String? = null
    ): ReceiptResponse

    @GET("receipts/json/fiscal/{id}")
    suspend fun getFiscalReceiptJson(
        @Path("id") id: String,
        @Query("copies") copies: Int = 1,
        @Query("paymentMethod") paymentMethod: String? = null
    ): ReceiptResponse

    // ── Payments ──

    @POST("payments")
    suspend fun initiatePayment(@Body request: InitiatePaymentRequest): PaymentDto

    @GET("payments/{id}")
    suspend fun getPayment(@Path("id") id: String): PaymentDto

    @POST("payments/{id}/result")
    suspend fun reportResult(
        @Path("id") id: String,
        @Body report: PaymentResultReport
    ): PaymentDto

    @GET("services/payments/payment/verify")
    suspend fun verifyPayment(
        @Query("transactionReference") transactionReference: String,
        @Query("paymentMethod") paymentMethod: String? = null
    ): GatewayVerifyResponse
}