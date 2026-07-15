package com.example.sunmisfe.payment

enum class PaymentMethod(val display: String, val wire: String) {
    Card("Carte bancaire", "Card"),
    MPesa("M-Pesa", "MPesa"),
    Airtel("Airtel Money", "Airtel"),
    Orange("Orange Money", "Orange"),
    Cash("Espèces", "Cash")
}