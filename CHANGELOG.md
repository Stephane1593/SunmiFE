# SunmiFE Changelog

All notable changes to this project will be documented in this file.

## [2.0.0] - 2026-07-15 🎯

### Receipt Printing Module
- **ADDED**
  - Full C# `ReceiptJsonBuilder` integration
  - Support for all `ReceiptElement` types:
    - `Text` → Plain text with alignment
    - `Row` → 2-column (left + right)
    - `ThreeCol` → 3-column layout
    - `DashLine`, `DoubleLine` → Separators
    - `Feed` → Blank lines
    - `QrCode` → Native Sunmi QR printing
    - `Logo` → Base64 bitmap printing
  - Styling properties: `bold`, `doubleSize`, `doubleHeight`
  - Alignment modes: `Left`, `Center`, `Right`
  - Retry logic for fiscal receipts (6 attempts)

- **FIXED**
  - Font size persistence across elements
  - QR code base64 decoding
  - Logo bitmap scaling on 58mm paper
  - Character width calculations

- **BREAKING CHANGES**
  - Element rendering now per-element (not concatenated)
  - Element types now PascalCase: `"Text"` not `"text"`
  - Alignment PascalCase: `"Left"` not `"left"`

### API Integration

GET /receipts/json/proforma/{orderId}?copies=N
GET /receipts/json/fiscal/{orderId}?copies=N


---

## [1.5.0] - 2026-06-30

### Added
- Coroutine-based async printing
- Timeout handling for buffer commits
- Retry logic for fiscal receipts
- Comprehensive diagnostic logging

---

## [1.0.0] - 2026-06-15

### Added
- Initial Sunmi printer service binding
- Basic text printing with alignment
- Test diagnostics