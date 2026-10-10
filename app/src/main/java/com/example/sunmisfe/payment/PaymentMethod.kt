package com.example.sunmisfe.payment

enum class PaymentMethod(val display: String, val wire: String) {
    Card("Carte bancaire", "Card"),
    MPesa("M-Pesa", "MPESA"),
    Airtel("Airtel Money", "AIRTEL"),
    Orange("Orange Money", "ORANGE"),
    Cash("Espèces", "Cash")
}