package com.example.sunmisfe.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface PaymentGatewayApi {

    @POST("services/payments/auth/login")
    suspend fun login(@Body body: GatewayLoginRequest): GatewayLoginResponse

    @POST("services/payments/payment/init")
    suspend fun initPayment(@Body body: GatewayInitRequest): GatewayInitResponse

    @GET("services/payments/payment/verify")
    suspend fun verifyPayment(
        @Query("transactionReference") transactionReference: String,
        @Query("paymentMethod") paymentMethod: String? = null
    ): GatewayVerifyResponse
}