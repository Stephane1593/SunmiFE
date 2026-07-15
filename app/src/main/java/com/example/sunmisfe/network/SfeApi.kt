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

interface SfeApi {

    @GET("orders")
    suspend fun getOrders(): List<OrderDto>

    @DELETE("orders/{id}")
    suspend fun clearOrder(@Path("id") id: String)

    // ── Receipts (ESC/POS bytes, streamed back as application/vnd.escpos) ──

    @Streaming
    @GET("receipts/proforma/{id}")
    suspend fun getProformaReceipt(@Path("id") id: String): Response<ResponseBody>

    @Streaming
    @GET("receipts/fiscal/{id}")
    suspend fun getFiscalReceipt(@Path("id") id: String): Response<ResponseBody>

    // ── NEW: Receipts as JSON (for Sunmi rendering) ──

    /**
     * Fetch proforma receipt as JSON structure.
     * Query param `copies` controls how many originals/duplicatas to generate.
     */
    @GET("receipts/json/proforma/{id}")
    suspend fun getProformaReceiptJson(
        @Path("id") id: String,
        @Query("copies") copies: Int = 1
    ): ReceiptResponse

    /**
     * Fetch fiscal receipt as JSON structure.
     * Query param `copies` controls how many originals/duplicatas to generate.
     */
    @GET("receipts/json/fiscal/{id}")
    suspend fun getFiscalReceiptJson(
        @Path("id") id: String,
        @Query("copies") copies: Int = 1
    ): ReceiptResponse

    // ── Payments (unchanged) ──

    @POST("payments")
    suspend fun initiatePayment(@Body request: InitiatePaymentRequest): PaymentDto

    @GET("payments/{id}")
    suspend fun getPayment(@Path("id") id: String): PaymentDto

    @POST("payments/{id}/result")
    suspend fun reportResult(
        @Path("id") id: String,
        @Body report: PaymentResultReport
    ): PaymentDto
}