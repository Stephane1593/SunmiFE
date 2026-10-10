package com.example.sunmisfe.network

import com.google.gson.annotations.SerializedName

// ---------- Auth ----------

data class GatewayLoginRequest(
    val login: String,
    val password: String
)

data class GatewayLoginResponse(
    val code: Int,
    val status: String? = null,
    val message: String = "",
    val token: String? = null
)

// ---------- Payment Init ----------

data class GatewayRouting(
    val provider: String,
    val providerOwnerId: Int? = null,
    val providerReference: String? = null
)

data class GatewayCustomer(
    val phone: String,
    val email: String? = null,
    val name: String? = null
)

data class GatewayInitRequest(
    val method: String,
    val amount: Double,
    val currency: String,
    val routing: GatewayRouting,
    val customer: GatewayCustomer,
    val orderReference: String? = null,
    val metadata: Map<String, @JvmSuppressWildcards Any>? = null
)

data class GatewayInitData(
    val transactionReference: String? = null,
    val gatewayReference: String? = null,
    val paymentMethod: String? = null,
    val status: String? = null,
    val gatewayResponse: Map<String, @JvmSuppressWildcards Any>? = null
)

data class GatewayInitResponse(
    val code: Int,
    val status: String = "",
    val message: String = "",
    val data: GatewayInitData? = null
)

// ---------- Payment Verify ----------


data class GatewayVerifyData(
    val transactionReference: String? = null,
    val paymentStatus: String? = null,
    val gatewayResponse: Map<String, @JvmSuppressWildcards Any>? = null
)

data class GatewayVerifyResponse(
    val code: Int,
    val status: String = "",
    val message: String = "",
    val data: GatewayVerifyData? = null
)

// ---------- Errors (optional, for structured error parsing) ----------

data class GatewayErrorResponse(
    val code: Int,
    val status: String = "",
    val message: String = "",
    val data: Map<String, @JvmSuppressWildcards Any>? = null
)