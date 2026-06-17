package com.pda.app.ui.i18n

/**
 * All user-facing UI strings, centralized for in-app language switching.
 * One implementation per language ([EnglishStrings] / [ChineseStrings] / [SpanishStrings]).
 *
 * Out of scope (NOT translated): server-returned error messages, carrier/condition values
 * ([com.pda.app.ui.dockreceiving.CARRIERS] / CONDITIONS), and any user input (tracking
 * numbers, batch numbers, counts) — those stay digits/English in every language.
 */
interface AppStrings {
    // ── Common ──────────────────────────────────────────────────────────────
    val common_retry: String
    val common_cancel: String
    val common_back: String          // contentDescription
    val common_loading: String

    /** Shared item count: Dock status bar, Batch Detail header, Receive Report totals. */
    fun itemCount(n: Int): String

    // ── Login ───────────────────────────────────────────────────────────────
    val login_subtitle: String
    val login_username: String
    val login_password: String
    val login_showPassword: String   // contentDescription
    val login_hidePassword: String   // contentDescription
    val login_rememberCredentials: String
    val login_loginButton: String
    val login_language: String

    // ── Home ────────────────────────────────────────────────────────────────
    val home_selectWarehouse: String
    val home_switchWarehouse: String // contentDescription
    fun home_greeting(name: String): String
    val home_selectWarehouseFirst: String
    val home_noWarehouses: String
    val home_dockReceive: String
    val home_receiveReport: String
    val home_logout: String          // contentDescription

    // ── Dock Receiving ────────────────────────────────────────────────────────
    val dock_title: String
    fun dock_batchTitle(number: String): String
    val dock_inputMethod: String
    val dock_inputMethodPicture: String
    val dock_inputMethodBarcode: String
    val dock_startBatch: String
    val dock_closeBatch: String
    val dock_close: String
    val dock_confirm: String
    val dock_scanHint: String
    val dock_trackingLabel: String
    val dock_carrier: String
    val dock_condition: String
    val dock_uploading: String
    val dock_analyzing: String
    val dock_uploadFailed: String
    val dock_saved: String
    val dock_needsReview: String     // contentDescription
    val dock_noTracking: String
    val dock_cameraPermission: String
    /** Full close-batch dialog body; each language owns its word order. */
    fun dock_closeBatchPrompt(itemCount: Int, needsReviewCount: Int): String
    val dock_selectWarehouseFirst: String
    val dock_photoProcessingFailed: String
    fun dock_batchClosed(number: String): String

    // ── Batch Detail ──────────────────────────────────────────────────────────
    val batch_empty: String
    val batch_noTracking: String
    val batch_needsReview: String

    // ── Receive Report ────────────────────────────────────────────────────────
    val report_title: String
    val report_empty: String
    val report_today: String
    val report_yesterday: String
    fun report_daySummary(batches: Int, items: Int): String
}
