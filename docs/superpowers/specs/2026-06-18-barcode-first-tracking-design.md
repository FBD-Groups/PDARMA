# Design: Barcode-First Tracking Number (Picture Mode)

**Date:** 2026-06-18
**Status:** Approved design — pending implementation plan

## Goal

In Dock Receiving **Picture mode**, read the tracking number from the shipping label's
**barcode** (decoded locally on the PDA) instead of relying on AI OCR of small digits.
Barcodes decode near-perfectly, so tracking-number accuracy improves dramatically. AI
(Gemini) is still used, but only for the supporting fields (carrier / service). When no
usable barcode is found, fall back to the AI-provided tracking number; if neither yields a
tracking number, the existing "not recognized" message prompts the user to retake or type.

## Non-Goals / Out of Scope

- **No backend change.** Gemini still returns tracking/carrier/service; the PDA prefers the
  barcode and uses AI's tracking only as a fallback. The `/api/analyze` prompt is unchanged.
- **Barcode Scan mode (hardware scanner) is unchanged.** This work only affects Picture mode.
- **Web app is unchanged.** If web wants barcode-first later, it gets its own implementation.
- No live-preview barcode scanning / auto-capture. Decoding happens on the captured still.

## Decisions (from brainstorming)

- Barcode source: **decode locally from the captured photo** (not hardware scanner, not live).
- Frontend (PDA), not backend — stronger decoder (ML Kit > ZXing.Net on real photos), can
  decode the **full-resolution** capture, zero added latency, offline.
- Fallback when no barcode: **use AI tracking**.
- **Fill fields only when the AI call completes** (single coherent population, no flicker) —
  the barcode result is held until then.

## Dependency

`com.google.mlkit:barcode-scanning` — the **bundled** ML Kit barcode model.

> Critical: Zebra enterprise devices typically lack Google Play Services. Use the **bundled**
> artifact (`com.google.mlkit:barcode-scanning`), which ships the model in the APK and works
> offline without Play Services. Do **NOT** use
> `com.google.android.gms:play-services-mlkit-barcode-scanning` (requires Play Services).

Declared in `gradle/libs.versions.toml` + referenced in `app/build.gradle.kts` (project
convention; no hard-coded versions in the build file). APK size +~2.4 MB.

## Components

### `BarcodeDecoder` (interface) + `MlKitBarcodeDecoder` (impl)

New package `com.pda.app.ui.dockreceiving` (alongside `ImageEncoder`). Single purpose:
decode a tracking-number barcode from an image file.

```kotlin
interface BarcodeDecoder {
    /** Decode the label's tracking-number barcode from [file]; null if none usable. */
    suspend fun decodeTracking(file: File): String?
}
```

`MlKitBarcodeDecoder`:
- Configures `BarcodeScannerOptions` with shipping formats: `CODE_128` (primary — USPS IMpb,
  UPS 1Z, FedEx), `CODE_39`, `CODE_93`, `ITF`, `PDF417`, `DATA_MATRIX`.
- `InputImage.fromFilePath(context, Uri.fromFile(file))` on the **full-resolution** capture.
- Awaits the scanner Task (suspend via `await()`), maps results to raw value strings, runs the
  pure `pickTrackingBarcode(...)` selector, returns the chosen string or null.
- Hilt `@Binds` like `AndroidImageEncoder`. Runs on `Dispatchers.IO`. Catches/logs and returns
  null on any failure (never throws into the capture flow).

### `pickTrackingBarcode(candidates: List<String>): String?` (pure function)

Disambiguates multiple barcodes on one label. Pure (JVM-only, unit-testable; no Android).

1. **Filter** each candidate through the existing `sanitizeTracking` (strip whitespace/hyphens,
   require 8–40 alphanumerics with ≥6 digits). Drop non-matches (short codes, routing digits).
2. **Select** among survivors:
   - Prefer a known carrier prefix: `1Z` (UPS); `94`/`92`/`93`/`95` (USPS IMpb); otherwise a
     plausible FedEx 12/15-digit all-numeric value.
   - Else pick the **longest** survivor (tracking is usually longer than routing codes).
3. Returns the **sanitized** (compact) form, or null if no survivor.

## Data Flow (`onPhotoCaptured`)

Three parallel coroutines, as today, plus barcode:

```
capture (original full-res JPEG file)
 ├─ runBarcode(file): MlKit decode  → store ConfirmState.barcodeTracking (NOT shown yet)
 ├─ runUpload(compressed bytes)     → photoPath
 └─ runAnalyze(compressed base64)   → carrier/service (+ AI tracking as fallback)
```

The visible fields populate **once the AI call completes** (success or error):

- **AI success** — precedence is **barcode > AI-found > existing field**:
  ```
  val aiTracking = sanitizeTracking(ai.trackingNumber)
  trackingNumber = barcodeTracking
        ?: aiTracking.ifBlank { c.trackingNumber }   // AI if it found one, else keep what's there
  carrier        = normalizeCarrier(ai.carrier).ifBlank { c.carrier }
  trackingFromBarcode = (barcodeTracking != null)
  ```
  Rationale: the barcode is authoritative for the label, so it wins even over a value the user
  typed while analyzing. If there is no barcode, AI fills when it found something; otherwise the
  user's typed value (if any) is preserved. If the result is blank → set
  `DockMessage.TrackingNotRecognized` (existing).
- **AI error (e.g., 502 overload):** if `barcodeTracking != null`, fill `trackingNumber` from
  it (carrier left blank, user may pick); else surface the error (existing behavior). A
  successful barcode is never wasted because AI failed.

**Race handling:** barcode decode is local (~ms) and almost always finishes before the AI
network call. The merge reads whatever `barcodeTracking` is present at AI completion. In the
rare case the barcode result arrives *after* AI completion and is non-null, it overrides the
field once (single extra update) — consistent with "barcode is authoritative", so both
orderings converge to the same result.

## State changes

`ConfirmState` gains:
- `barcodeTracking: String? = null` — held barcode result before the merge.
- `trackingFromBarcode: Boolean = false` — drives `source` at save time.

`saveItem`: `source = if (c.trackingFromBarcode) "Barcode" else "AI"` (matches the existing
Barcode Scan mode's tagging). `needsReview` stays `tracking.isBlank()`.

## DI / wiring

- `MlKitBarcodeDecoder` injected into `DockReceivingViewModel` alongside `ImageEncoder`.
- `@Binds` in the existing image-encoder Hilt module (or a sibling) binds
  `BarcodeDecoder` → `MlKitBarcodeDecoder`.
- Tests inject a `FakeBarcodeDecoder` (same pattern as `FakeImageEncoder`).

## Testing

- **`BarcodePickTest`** (pure): multi-barcode selection — UPS `1Z` prefix wins over routing
  code; USPS `94…` IMpb chosen; longest-wins tiebreak; all-invalid → null; empty → null.
- **`DockReceivingViewModelTest`** (extend): with `FakeBarcodeDecoder`:
  - barcode hit overrides AI tracking; `source="Barcode"`, `trackingFromBarcode=true`.
  - barcode null → falls back to AI tracking; `source="AI"`.
  - barcode null + AI null → `DockMessage.TrackingNotRecognized`.
  - AI error but barcode hit → tracking still filled from barcode.
  - Fields populate only at AI completion (barcode held until then).
- ML Kit actual decoding is not unit-tested (on-device verification).

## Files

**New:** `BarcodeDecoder.kt` (interface + `pickTrackingBarcode` + Hilt `@Binds`),
`MlKitBarcodeDecoder.kt`; test `BarcodePickTest.kt`.
**Modified:** `gradle/libs.versions.toml`, `app/build.gradle.kts` (dependency);
`DockReceivingUiState.kt` (`barcodeTracking`, `trackingFromBarcode`);
`DockReceivingViewModel.kt` (`runBarcode`, hold-and-merge, `source`);
`DockReceivingViewModelTest.kt` (+ `FakeBarcodeDecoder`, new cases).

**Unchanged:** backend, Gemini prompt, Barcode Scan mode, web app, screen layout
(fields already render; only their populated values change).
