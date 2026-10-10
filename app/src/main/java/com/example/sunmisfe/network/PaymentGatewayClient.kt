package com.example.sunmisfe.network

import android.util.Log
import com.example.sunmisfe.data.TerminalConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Client for the external Payments Gateway (mobile money).
 * Rebuilt whenever gateway URL changes; JWT token kept in memory only
 * (never persisted, never logged) and refreshed lazily on 401 / expiry.
 */
object PaymentGatewayClient {

    private const val TAG = "PaymentGatewayClient"

    @Volatile private var builtForUrl: String? = null
    @Volatile private var _api: PaymentGatewayApi? = null
    @Volatile private var token: String? = null

    private val tokenMutex = Mutex()
    private val buildLock = Any()

    private val api: PaymentGatewayApi
        get() {
            val url = TerminalConfig.gatewayUrl
            if (_api == null || builtForUrl != url) rebuild(url)
            return _api!!
        }

    private fun rebuild(url: String) = synchronized(buildLock) {
        // double-checked after acquiring the lock
        if (_api != null && builtForUrl == url) return@synchronized

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(AuthHeaderInterceptor())
            .build()

        _api = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PaymentGatewayApi::class.java)
        builtForUrl = url
    }

    /** Ensures we have a valid token, logging in if necessary. Never logs the password. */
    private suspend fun ensureToken(forceRefresh: Boolean = false): String = tokenMutex.withLock {
        val existing = token
        if (existing != null && !forceRefresh) return@withLock existing

        if (!TerminalConfig.hasGatewayCredentials()) {
            error("Identifiants de la passerelle de paiement non configurés")
        }

        Log.d(TAG, "Logging in to payment gateway as ${TerminalConfig.gatewayLogin}")
        val resp = api.login(
            GatewayLoginRequest(
                login = TerminalConfig.gatewayLogin,
                password = TerminalConfig.gatewayPassword
            )
        )
        val t = resp.token
        if (resp.code != 200 || t.isNullOrBlank()) {
            throw IllegalStateException(resp.message.ifBlank { "Échec d'authentification passerelle" })
        }
        token = t
        t
    }

    suspend fun initPayment(req: GatewayInitRequest): GatewayInitResponse {
        ensureToken()
        return try {
            api.initPayment(req)
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401) {
                ensureToken(forceRefresh = true)
                api.initPayment(req)
            } else throw e
        }
    }

    suspend fun verifyPayment(transactionReference: String, paymentMethod: String? = null): GatewayVerifyResponse {
        ensureToken()
        return try {
            api.verifyPayment(transactionReference, paymentMethod)
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401) {
                ensureToken(forceRefresh = true)
                api.verifyPayment(transactionReference, paymentMethod)
            } else throw e
        }
    }

    /** Call after Settings changes gateway URL/credentials. */
    @Synchronized
    fun invalidate() {
        token = null
        _api = null
        builtForUrl = null
    }

    private class AuthHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request().newBuilder()
                .apply { token?.let { header("token", it) } }
                .build()
            return chain.proceed(req)
        }
    }
}