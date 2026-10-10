package com.example.sunmisfe.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object TerminalConfig {

    private const val PREFS = "sfe_terminal_secure"

    private const val KEY_TILL_URL       = "till_url"
    private const val KEY_TERMINAL_ID    = "terminal_id"
    private const val KEY_GATEWAY_URL    = "gateway_url"
    private const val KEY_GATEWAY_LOGIN  = "gateway_login"
    private const val KEY_GATEWAY_PASS   = "gateway_password"
    private const val KEY_APP_THEME      = "app_theme" // 👈 New

    const val DEFAULT_TILL_URL    = "http://192.168.1.77:5005/"
    const val DEFAULT_GATEWAY_URL = "http://34.72.60.38/logiref-v4.0.1/"

    @Volatile var tillUrl: String = DEFAULT_TILL_URL
        private set
    @Volatile var terminalId: String = ""
        private set
    @Volatile var gatewayUrl: String = DEFAULT_GATEWAY_URL
        private set
    @Volatile var gatewayLogin: String = ""
        private set
    @Volatile var gatewayPassword: String = ""
        private set
    @Volatile var appTheme: String = "BLUE" // 👈 New
        private set

    @Volatile private var initialized = false

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        PREFS,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        ctx.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        val p = prefs(ctx)
        tillUrl         = normalize(p.getString(KEY_TILL_URL, null) ?: DEFAULT_TILL_URL)
        terminalId      = p.getString(KEY_TERMINAL_ID, "").orEmpty()
        gatewayUrl      = normalize(p.getString(KEY_GATEWAY_URL, null) ?: DEFAULT_GATEWAY_URL)
        gatewayLogin    = p.getString(KEY_GATEWAY_LOGIN, "").orEmpty()
        gatewayPassword = p.getString(KEY_GATEWAY_PASS, "").orEmpty()
        appTheme        = p.getString(KEY_APP_THEME, "BLUE") ?: "BLUE" // 👈 New
        initialized = true
    }

    @Synchronized
    fun updateTill(ctx: Context, url: String, id: String) {
        tillUrl    = normalize(url.ifBlank { DEFAULT_TILL_URL })
        terminalId = id.trim()
        prefs(ctx).edit()
            .putString(KEY_TILL_URL, tillUrl)
            .putString(KEY_TERMINAL_ID, terminalId)
            .apply()
    }

    @Synchronized
    fun updateGateway(ctx: Context, url: String, login: String, password: String) {
        gatewayUrl      = normalize(url.ifBlank { DEFAULT_GATEWAY_URL })
        gatewayLogin    = login.trim()
        gatewayPassword = password
        prefs(ctx).edit()
            .putString(KEY_GATEWAY_URL, gatewayUrl)
            .putString(KEY_GATEWAY_LOGIN, gatewayLogin)
            .putString(KEY_GATEWAY_PASS, gatewayPassword)
            .apply()
    }

    // 👈 New method
    @Synchronized
    fun updateTheme(ctx: Context, theme: String) {
        appTheme = theme
        prefs(ctx).edit().putString(KEY_APP_THEME, theme).apply()
    }

    fun hasGatewayCredentials(): Boolean =
        gatewayLogin.isNotBlank() && gatewayPassword.isNotBlank()

    private fun normalize(url: String): String =
        if (url.endsWith("/")) url else "$url/"
}