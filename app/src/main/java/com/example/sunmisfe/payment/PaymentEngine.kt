package com.example.sunmisfe.payment

import android.util.Log
import com.example.sunmisfe.data.TerminalConfig
import com.example.sunmisfe.network.GatewayCustomer
import com.example.sunmisfe.network.GatewayInitRequest
import com.example.sunmisfe.network.GatewayRouting
import com.example.sunmisfe.network.InitiatePaymentRequest
import com.example.sunmisfe.network.OrderDto
import com.example.sunmisfe.network.PaymentGatewayClient
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
    data object AwaitingApproval : PaymentEvent           // Card: operator presses Approve/Decline
    data object AwaitingCustomerConfirmation : PaymentEvent // Mobile money: customer confirms on phone
    data class Processing(val message: String) : PaymentEvent
    data class Succeeded(val authCode: String, val transactionRef: String) : PaymentEvent
    data class Failed(val reason: String) : PaymentEvent
}

/** Extra input required before/along with [PaymentEngine.pay] for certain methods. */
sealed interface PaymentExtra {
    data class MobileMoney(val phone: String) : PaymentExtra
    data class Cash(val tenderedAmount: Double) : PaymentExtra
}

object PaymentEngine {

    private const val TAG = "PaymentEngine"
    private const val SIMULATE_LOCALLY = false

    // Polling config for mobile money
    private const val VERIFY_POLL_INTERVAL_MS = 3_000L
    private const val VERIFY_TIMEOUT_MS = 90_000L

    // ── Bridge between the UI Approve/Decline buttons and the running Card flow ──
    private var pending: CompletableDeferred<String>? = null
    fun approve() { pending?.complete("Approved") }
    fun decline() { pending?.complete("Declined") }

    fun pay(
        order: OrderDto,
        method: PaymentMethod,
        extra: PaymentExtra? = null,
        terminalId: String = TerminalConfig.terminalId
    ): Flow<PaymentEvent> {
        val base = when {
            SIMULATE_LOCALLY -> simulate(order, method)
            method == PaymentMethod.Cash -> cash(order, extra as? PaymentExtra.Cash, terminalId)
            method.isMobileMoney() -> mobileMoney(order, method, extra as? PaymentExtra.MobileMoney, terminalId)
            else -> card(order, method, terminalId) // Card
        }
        return base.catch { e ->
            Log.e(TAG, "Uncaught in payment flow", e)
            emit(PaymentEvent.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun PaymentMethod.isMobileMoney() =
        this == PaymentMethod.MPesa || this == PaymentMethod.Airtel || this == PaymentMethod.Orange

    // ── SIMULATION (kept for offline dev/testing) ──────────────
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

    // ── CARD — unchanged behaviour: till API + operator Approve/Decline ──
    private fun card(
        order: OrderDto,
        method: PaymentMethod,
        terminalId: String
    ): Flow<PaymentEvent> = flow {
        val api = SfeApiClient.api
        val idempotencyKey = order.orderId

        emit(PaymentEvent.Initiating)
        Log.d(TAG, "POST /payments key=$idempotencyKey order=${order.orderId} amount=${order.amount}")

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

        emit(PaymentEvent.AwaitingApproval)
        val decision = CompletableDeferred<String>().also { pending = it }
        val wireStatus = try { decision.await() } finally { pending = null }

        emit(PaymentEvent.Processing("Notification de la caisse..."))
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

    // ── CASH — operator enters tendered amount, no gateway involved ──
    private fun cash(
        order: OrderDto,
        extra: PaymentExtra.Cash?,
        terminalId: String
    ): Flow<PaymentEvent> = flow {
        val tendered = extra?.tenderedAmount
        if (tendered == null || tendered < order.amount) {
            emit(PaymentEvent.Failed("Montant reçu insuffisant"))
            return@flow
        }

        val api = SfeApiClient.api
        val idempotencyKey = order.orderId

        emit(PaymentEvent.Initiating)
        val initiated = try {
            api.initiatePayment(
                InitiatePaymentRequest(
                    idempotencyKey = idempotencyKey,
                    orderId        = order.orderId,
                    amount         = order.amount,
                    method         = PaymentMethod.Cash.wire,
                    terminalId     = terminalId,
                    currency       = order.currency
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "cash initiatePayment failed", e)
            emit(PaymentEvent.Failed("Réseau : ${e.message ?: e.javaClass.simpleName}"))
            return@flow
        }

        val txId = initiated.idempotencyKey.ifBlank { idempotencyKey }

        emit(PaymentEvent.Processing("Enregistrement du paiement en espèces..."))
        val reported = runCatching {
            api.reportResult(
                txId,
                PaymentResultReport(
                    status = "Approved",
                    providerRef = "CASH-${txId.take(6)}",
                    reason = null
                )
            )
        }
        if (reported.isFailure) {
            Log.w(TAG, "cash reportResult failed", reported.exceptionOrNull())
            emit(PaymentEvent.Failed("Impossible de notifier la caisse"))
            return@flow
        }

        emit(PaymentEvent.Succeeded(
            authCode = "CASH-${txId.take(6).uppercase()}",
            transactionRef = txId
        ))
        runCatching { SfeApiClient.api.clearOrder(order.orderId) }
    }

    // ── MOBILE MONEY — Payments Gateway API + poll + report to till ──
    private fun mobileMoney(
        order: OrderDto,
        method: PaymentMethod,
        extra: PaymentExtra.MobileMoney?,
        terminalId: String
    ): Flow<PaymentEvent> = flow {
        val phone = extra?.phone?.trim().orEmpty()
        if (phone.isBlank()) {
            emit(PaymentEvent.Failed("Numéro de téléphone requis"))
            return@flow
        }

        val currency = order.currency.ifBlank {
            Log.e(TAG, "order.currency is blank for order=${order.orderId}")
            emit(PaymentEvent.Failed("Devise manquante sur la commande"))
            return@flow
        }

        val till = SfeApiClient.api
        val idempotencyKey = order.orderId

        // 1. Tell the till a payment attempt is starting
        emit(PaymentEvent.Initiating)
        val initiatedTill = try {
            till.initiatePayment(
                InitiatePaymentRequest(
                    idempotencyKey = idempotencyKey,
                    orderId        = order.orderId,
                    amount         = order.amount,
                    method         = method.wire,
                    terminalId     = terminalId,
                    currency       = currency
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "till initiatePayment failed", e)
            emit(PaymentEvent.Failed("Réseau caisse : ${e.message ?: e.javaClass.simpleName}"))
            return@flow
        }
        val txId = initiatedTill.idempotencyKey.ifBlank { idempotencyKey }

        // 2. Initiate on the payment gateway
        emit(PaymentEvent.Processing("${method.display} — envoi de la demande de paiement..."))
        Log.d(TAG, "Gateway init order=${order.orderId} provider=${method.wire} phone=${maskPhone(phone)}")

        val gatewayInit = try {
            PaymentGatewayClient.initPayment(
                GatewayInitRequest(
                    method = "WALLET",
                    amount = order.amount,
                    currency = currency,
                    routing = GatewayRouting(provider = method.wire),
                    customer = GatewayCustomer(phone = phone),
                    orderReference = order.orderId
                )
            )
        } catch (e: retrofit2.HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            Log.e(TAG, "gateway init failed: HTTP ${e.code()} body=$body")
            reportFailureToTill(till, txId, "Passerelle HTTP ${e.code()}: ${body ?: e.message()}")
            emit(PaymentEvent.Failed("Passerelle : ${body ?: e.message()}"))
            return@flow
        } catch (e: Exception) {
            Log.e(TAG, "gateway init failed", e)
            reportFailureToTill(till, txId, "Échec d'initiation passerelle")
            emit(PaymentEvent.Failed("Passerelle : ${e.message ?: e.javaClass.simpleName}"))
            return@flow
        }

        val orderRef   = gatewayInit.data?.transactionReference
        val gatewayRef = gatewayInit.data?.gatewayReference

        if (gatewayInit.code != 200 || orderRef.isNullOrBlank()) {
            val reason = gatewayInit.message.ifBlank { "Échec d'initiation du paiement" }
            reportFailureToTill(till, txId, reason)
            emit(PaymentEvent.Failed(reason))
            return@flow
        }
        Log.d(TAG, "Gateway init OK orderRef=$orderRef gatewayRef=${gatewayRef ?: "-"} status=${gatewayInit.data?.status}")

        // 3. Wait for the customer to confirm on their phone (USSD/STK push), poll verify.
        //    Using order.orderId to reliably check the status as per the gateway API doc
        val verifyRef = order.orderId

        emit(PaymentEvent.AwaitingCustomerConfirmation)

        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        var finalStatus: String? = null

        while (System.currentTimeMillis() < deadline) {
            delay(VERIFY_POLL_INTERVAL_MS)

            var currentStatus: String? = null

            try {
                // Success response (HTTP 200)
                val verify = PaymentGatewayClient.verifyPayment(verifyRef, "WALLET")
                currentStatus = verify.data?.paymentStatus ?: verify.status
            }
            catch (e: retrofit2.HttpException) {
                // 💡 Handle gateway HTTP Errors (400, 502) to break polling instantly
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
                Log.w(TAG, "verify poll HTTP ${e.code()} body=$body")

                if (body != null && body.contains("\"status\":\"ERROR\"", ignoreCase = true)) {
                    currentStatus = "ERROR"
                } else if (e.code() == 400 || e.code() == 502) {
                    currentStatus = "ERROR"
                }
            }
            catch (e: Exception) {
                Log.w(TAG, "verify poll network error (will retry)", e)
            }

            Log.d(TAG, "verify poll resolved status=$currentStatus")

            when (currentStatus?.uppercase()) {
                "APPROVED", "SUCCESS" -> {
                    finalStatus = "Approved"
                    break
                }
                "DECLINED", "FAILED", "REJECTED", "CANCELLED", "ERROR" -> {
                    finalStatus = "Declined"
                    break
                }
                else -> {
                    emit(PaymentEvent.Processing("En attente de confirmation du client..."))
                }
            }
        }

        if (finalStatus == null) {
            reportFailureToTill(till, txId, "Délai dépassé")
            emit(PaymentEvent.Failed("Délai d'attente dépassé — le client n'a pas confirmé"))
            return@flow
        }

        // 4. Report the final outcome to the till.
        emit(PaymentEvent.Processing("Notification de la caisse..."))
        val providerRef = gatewayRef?.ifBlank { null } ?: orderRef
        val reported = runCatching {
            till.reportResult(
                txId,
                PaymentResultReport(
                    status = finalStatus,
                    providerRef = providerRef,
                    reason = if (finalStatus == "Declined") "Refusé par le fournisseur mobile money" else null
                )
            )
        }
        if (reported.isFailure) {
            Log.w(TAG, "reportResult failed", reported.exceptionOrNull())
            emit(PaymentEvent.Failed("Impossible de notifier la caisse"))
            return@flow
        }

        if (finalStatus == "Approved") {
            val display = gatewayRef?.ifBlank { null } ?: orderRef
            emit(PaymentEvent.Succeeded(
                authCode       = "MM-${display.take(6).uppercase()}",
                transactionRef = orderRef
            ))
            runCatching { till.clearOrder(order.orderId) }
        } else {
            emit(PaymentEvent.Failed("Paiement refusé par le client / le fournisseur"))
        }
    }

    private suspend fun reportFailureToTill(till: com.example.sunmisfe.network.SfeApi, txId: String, reason: String) {
        runCatching {
            till.reportResult(txId, PaymentResultReport(status = "Declined", providerRef = null, reason = reason))
        }
    }

    /** Mask all but the last 3 digits — never log full phone numbers. */
    private fun maskPhone(phone: String): String =
        if (phone.length <= 3) "***" else "***${phone.takeLast(3)}"
}