package com.example.sunmisfe.payment

import android.util.Log
import com.example.sunmisfe.network.InitiatePaymentRequest
import com.example.sunmisfe.network.OrderDto
import com.example.sunmisfe.network.PaymentResultReport
import com.example.sunmisfe.network.SfeApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.util.UUID

sealed interface PaymentEvent {
    data object Initiating : PaymentEvent
    data object AwaitingApproval : PaymentEvent
    data class Processing(val message: String) : PaymentEvent
    data class Succeeded(val authCode: String, val transactionRef: String) : PaymentEvent
    data class Failed(val reason: String) : PaymentEvent
}

object PaymentEngine {

    private const val TAG = "PaymentEngine"

    /**
     *  true  → local simulation.
     *  false → real handshake with the .NET LAN API.
     */
    private const val SIMULATE_LOCALLY = false

    // ── Bridge between the UI buttons and the running payment flow ──
    private var pending: CompletableDeferred<String>? = null
    fun approve() { pending?.complete("Approved") }
    fun decline() { pending?.complete("Declined") }

    fun pay(
        order: OrderDto,
        method: PaymentMethod,
        terminalId: String = SfeApiClient.terminalId
    ): Flow<PaymentEvent> {
        val base = if (SIMULATE_LOCALLY) simulate(order, method)
        else live(order, method, terminalId)
        return base.catch { e ->
            Log.e(TAG, "Uncaught in payment flow", e)
            emit(PaymentEvent.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    // ── SIMULATION ────────────────────────────────────────────
    private fun simulate(order: OrderDto, method: PaymentMethod): Flow<PaymentEvent> = flow {
        emit(PaymentEvent.Initiating)
        delay(600)
        emit(PaymentEvent.Processing("${method.display} — contact avec l'acquéreur..."))
        delay(1500)
        emit(PaymentEvent.Processing("Autorisation en cours..."))
        delay(1500)

        if ("%.2f".format(order.amount).endsWith(".99")) {
            emit(PaymentEvent.Failed("Fonds insuffisants (simulé)"))
        } else {
            emit(
                PaymentEvent.Succeeded(
                    authCode = "AUTH${(100000..999999).random()}",
                    transactionRef = "TX-${UUID.randomUUID().toString().take(8).uppercase()}"
                )
            )
            runCatching { SfeApiClient.api.clearOrder(order.orderId) }
        }
    }

    // ── LIVE (real C# endpoints) ──────────────────────────────
    private fun live(
        order: OrderDto,
        method: PaymentMethod,
        terminalId: String
    ): Flow<PaymentEvent> = flow {
        val api = SfeApiClient.api
        val idempotencyKey = order.orderId

        emit(PaymentEvent.Initiating)
        Log.d(TAG, "POST /payments key=$idempotencyKey order=${order.orderId} amount=${order.amount}")

        // ── Initiate ──
        val initiated = try {
            api.initiatePayment(
                InitiatePaymentRequest(
                    idempotencyKey = idempotencyKey,
                    orderId        = order.orderId,
                    amount         = order.amount,
                    method         = method.wire,
                    terminalId     = terminalId,
                    currency       = order.currency
                )
            )
        } catch (e: retrofit2.HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            Log.e(TAG, "initiatePayment failed: HTTP ${e.code()} body=$body")
            emit(PaymentEvent.Failed("Init HTTP ${e.code()} — ${body ?: e.message()}"))
            return@flow
        } catch (e: Exception) {
            Log.e(TAG, "initiatePayment network error", e)
            emit(PaymentEvent.Failed("Réseau : ${e.message ?: e.javaClass.simpleName}"))
            return@flow
        }

        val txId = initiated.idempotencyKey.ifBlank { idempotencyKey }

        // ── Wait for the operator to press Approve/Decline on this terminal ──
        emit(PaymentEvent.AwaitingApproval)
        val decision = CompletableDeferred<String>().also { pending = it }
        val wireStatus = try { decision.await() } finally { pending = null }

        // ── Report the outcome to the till ──
        emit(PaymentEvent.Processing("Notification de la caisse..."))
        Log.d(TAG, "Reporting $wireStatus for $txId")
        val reported = runCatching {
            api.reportResult(
                txId,
                PaymentResultReport(
                    status      = wireStatus,
                    providerRef = "SUNMI-${txId.take(6)}",
                    reason      = if (wireStatus == "Declined") "Refusé sur terminal" else null
                )
            )
        }
        if (reported.isFailure) {
            Log.w(TAG, "reportResult failed", reported.exceptionOrNull())
            emit(PaymentEvent.Failed("Impossible de notifier la caisse"))
            return@flow
        }

        // ── Emit final ──
        if (wireStatus == "Approved") {
            emit(PaymentEvent.Succeeded(
                authCode       = "AUTH-${txId.take(6).uppercase()}",
                transactionRef = txId
            ))
            runCatching { SfeApiClient.api.clearOrder(order.orderId) }
        } else {
            emit(PaymentEvent.Failed("Refusé sur terminal"))
        }
    }
}