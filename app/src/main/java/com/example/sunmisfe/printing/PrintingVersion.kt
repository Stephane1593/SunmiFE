package com.example.sunmisfe.printing

/**
 * Version control for receipt printing module.
 *
 * Update these on every change:
 * - MAJOR: Breaking changes (new element types, enum renames)
 * - MINOR: New features (new alignment, styling options)
 * - PATCH: Bug fixes, optimizations
 * - DATE: Release date
 */
object PrintingVersion {
    const val MAJOR = 2
    const val MINOR = 0
    const val PATCH = 0
    const val DATE = "2026-07-15"

    const val VERSION_STRING = "$MAJOR.$MINOR.$PATCH"

    // ═════════════════════════════════════════════════════════════════
    // CHANGELOG
    // ═════════════════════════════════════════════════════════════════
    /**
     * v2.0.0 (2026-07-15) — JSON Builder Integration
     * - ✅ Integrated with C# ReceiptJsonBuilder
     * - ✅ Full support for ReceiptDocument & ReceiptElement
     * - ✅ Styling: bold, doubleSize, doubleHeight
     * - ✅ Element types: Text, Row, ThreeCol, DashLine, DoubleLine, Feed, QrCode, Logo
     * - ✅ Native QR code printing via Sunmi SDK
     * - ✅ Base64 logo decoding & bitmap printing
     * - ✅ Proper alignment (Left, Center, Right)
     * - ✅ Proforma & Fiscal with retry logic
     * - 🐛 Fixed: Multiple element rendering per document
     * - 🐛 Fixed: Font size persistence across elements
     *
     * v1.5.0 (2026-06-30) — Buffer Management
     * - ✅ Timeout handling for printer buffer commits
     * - ✅ Coroutine-based async printing
     * - ✅ Sanitization of Unicode characters
     *
     * v1.0.0 (2026-06-15) — Initial Release
     * - ✅ Basic Sunmi printer binding
     * - ✅ Text printing with alignment
     * - ✅ Test diagnostics
     */

    fun getVersionInfo(): String = """
        ╔═══════════════════════════════════════════════════════════╗
        ║        Receipt Printing Module v$VERSION_STRING                ║
        ║                    Released: $DATE                      ║
        ╚═══════════════════════════════════════════════════════════╝
        
        Supported Element Types:
        • Text       → Plain text with alignment & styling
        • Row        → 2-column layout (left + right)
        • ThreeCol   → 3-column layout
        • DashLine   → Separator line (─)
        • DoubleLine → Double separator (═)
        • Feed       → Blank lines
        • QrCode     → Native QR code
        • Logo       → Bitmap image
        
        Styling Options:
        • bold: Boolean
        • doubleSize: Boolean
        • doubleHeight: Boolean
        
        API Endpoints:
        • /receipts/json/proforma/{orderId}?copies=N
        • /receipts/json/fiscal/{orderId}?copies=N
        
        Status: ✅ Production Ready
    """.trimIndent()
}