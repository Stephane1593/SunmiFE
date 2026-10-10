package com.example.sunmisfe.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object SfeApiClient {

    private const val PREFS       = "sfe_terminal"
    private const val KEY_URL     = "api_url"
    private const val KEY_ID      = "terminal_id"
    private const val DEFAULT_URL = "http://192.168.1.77:5005/"

    @Volatile private var baseUrl: String = DEFAULT_URL
    @Volatile var terminalId: String = ""
        private set

    @Volatile private var _api: SfeApi? = null
    val api: SfeApi get() = _api ?: rebuild()

    /** Call once from MainActivity.onCreate. */
    fun init(ctx: Context) {
        val p = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseUrl    = normalize(p.getString(KEY_URL, DEFAULT_URL).orEmpty()
            .ifBlank { DEFAULT_URL })
        terminalId = p.getString(KEY_ID, "").orEmpty()
        rebuild()
    }

    /** Called by the Settings screen. */
    fun updateConfig(ctx: Context, url: String, id: String) {
        baseUrl    = normalize(url.ifBlank { DEFAULT_URL })
        terminalId = id.trim()
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, baseUrl)
            .putString(KEY_ID,  terminalId)
            .apply()
        rebuild()
    }

    fun currentUrl(): String = baseUrl

    @Synchronized
    private fun rebuild(): SfeApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(TerminalHeaderInterceptor())
            .build()
        val built = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SfeApi::class.java)
        _api = built
        return built
    }

    private fun normalize(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    private class TerminalHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val req = chain.request().newBuilder()
                .header("X-Terminal-Id", terminalId)
                .build()
            return chain.proceed(req)
        }
    }

    suspend fun cancelOrderSafe(orderId: String): Boolean {
        return try {
            val response = api.cancelOrder(orderId)
            response.isSuccessful
        } catch (e: Exception) {
            android.util.Log.e("SfeApiClient", "Cancel order failed", e)
            false
        }
    }
}