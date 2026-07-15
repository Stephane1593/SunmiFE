package com.example.sunmisfe.network

/**
 * Matches C# InitiatePaymentRequest(
 *     string IdempotencyKey, string OrderId, decimal Amount, string Method).
 *
 * `amount` is non-nullable — C# Amount is a non-nullable decimal and STJ will
 * throw on null. `terminalId`/`currency` don't exist on the C# record; they're
 * kept only for local logging and will be silently ignored by the server.
 */
data class InitiatePaymentRequest(
    val idempotencyKey: String,
    val orderId: String,
    val amount: Double,
    val method: String,
    val terminalId: String? = null,
    val currency: String? = null
)

/**
 * Matches C# PaymentTransactionDto(
 *     string IdempotencyKey, string OrderId, decimal Amount, string Method,
 *     string Status, string? ProviderRef, string? FailureReason).
 *
 * ‼ There is NO `id` field on the wire — the idempotency key IS the identifier.
 *   Use `id` (alias below) or `idempotencyKey` for GET /payments/{id} and
 *   POST /payments/{id}/result.
 *
 * Valid `status` values (JsonStringEnumConverter server-side):
 *   "Initiated", "Processing", "Approved", "Declined", "TimedOut", "Reconciled".
 */
data class PaymentDto(
    val idempotencyKey: String,
    val orderId: String,
    val amount: Double,
    val method: String,
    val status: String,
    val providerRef: String? = null,
    val failureReason: String? = null
) {
    /** The identifier used in follow-up URLs. Same value as idempotencyKey. */
    val id: String get() = idempotencyKey

    /** Convenience aliases so UI/log code reads naturally. */
    val authCode: String?       get() = providerRef
    val transactionRef: String? get() = providerRef
    val errorMessage: String?   get() = failureReason
}

/**
 * Matches C# PaymentResultReport(
 *     PaymentTransactionStatus Status, string? ProviderRef, string? Reason).
 *
 * Server uses JsonStringEnumConverter, so `status` is the enum NAME.
 * For a terminal outcome report only "Approved" or "Declined" are meaningful.
 */
data class PaymentResultReport(
    val status: String,
    val providerRef: String? = null,
    val reason: String? = null
)