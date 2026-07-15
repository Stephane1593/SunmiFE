package com.example.sunmisfe.network

import com.google.gson.annotations.SerializedName

data class ReceiptDocument(
    @SerializedName("width")
    val width: Int = 48,

    @SerializedName("isProforma")
    val isProforma: Boolean = false,

    @SerializedName("isDuplicate")
    val isDuplicate: Boolean = false,

    @SerializedName("footerText")
    val footerText: String = "",

    @SerializedName("printedAt")
    val printedAt: String = "",

    @SerializedName("elements")
    val elements: List<ReceiptElement> = emptyList()
)

/**
 * Matches C# ReceiptElement exactly.
 */
data class ReceiptElement(
    @SerializedName("type")
    val type: String,

    @SerializedName("text")  // ← Changed from "content"
    val text: String? = null,

    @SerializedName("left")  // ← Changed from "leftText"
    val left: String? = null,

    @SerializedName("middle")  // ← Changed from "middleText"
    val middle: String? = null,

    @SerializedName("right")  // ← Changed from "rightText"
    val right: String? = null,

    @SerializedName("align")
    val align: String = "Left",  // ← Capitalized to match C#

    @SerializedName("bold")
    val bold: Boolean = false,

    @SerializedName("doubleSize")
    val doubleSize: Boolean = false,

    @SerializedName("doubleHeight")
    val doubleHeight: Boolean = false,

    @SerializedName("feedLines")
    val feedLines: Int = 1
) {
    companion object {
        // Match C# enum exactly (PascalCase)
        const val TYPE_TEXT = "Text"
        const val TYPE_ROW = "Row"
        const val TYPE_THREE_COL = "ThreeCol"
        const val TYPE_DASH_LINE = "DashLine"
        const val TYPE_DOUBLE_LINE = "DoubleLine"
        const val TYPE_FEED = "Feed"
        const val TYPE_QR_CODE = "QrCode"
        const val TYPE_LOGO = "Logo"

        const val ALIGN_LEFT = "Left"
        const val ALIGN_CENTER = "Center"
        const val ALIGN_RIGHT = "Right"
    }
}

data class ReceiptResponse(
    @SerializedName("documents")
    val documents: List<ReceiptDocument> = emptyList()
)