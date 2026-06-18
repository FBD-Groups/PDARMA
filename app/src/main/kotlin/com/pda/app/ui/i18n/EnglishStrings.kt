package com.pda.app.ui.i18n

object EnglishStrings : AppStrings {
    override val common_retry = "Retry"
    override val common_cancel = "Cancel"
    override val common_back = "Back"
    override val common_loading = "Loading…"
    override val common_sessionExpired = "Session expired, please sign in again"
    override fun itemCount(n: Int) = "$n items"

    override val login_subtitle = "Warehouse Management System"
    override val login_username = "Username"
    override val login_password = "Password"
    override val login_showPassword = "Show password"
    override val login_hidePassword = "Hide password"
    override val login_rememberCredentials = "Remember credentials"
    override val login_loginButton = "Sign In"
    override val login_language = "Language"

    override val home_selectWarehouse = "Select warehouse"
    override val home_switchWarehouse = "Switch warehouse"
    override fun home_greeting(name: String) = "Hello, $name"
    override val home_selectWarehouseFirst = "Please select a warehouse first"
    override val home_noWarehouses = "No warehouses available"
    override val home_dockReceive = "Dock Receive"
    override val home_receiveReport = "Receive Report"
    override val home_logout = "Logout"

    override val dock_title = "Dock Receive"
    override fun dock_batchTitle(number: String) = "Batch $number"
    override val dock_inputMethod = "Input Method"
    override val dock_inputMethodPicture = "Picture"
    override val dock_inputMethodBarcode = "Barcode Scan"
    override val dock_startBatch = "Start Batch"
    override val dock_closeBatch = "Close Batch"
    override val dock_close = "Close"
    override val dock_confirm = "Confirm"
    override val dock_scanHint = "Scan or enter tracking #…"
    override val dock_trackingLabel = "Tracking #"
    override val dock_carrier = "Carrier"
    override val dock_condition = "Condition"
    override val dock_barcodeScanning = "Barcode…"
    override val dock_barcodeOk = "Barcode OK"
    override val dock_barcodeNotFound = "No barcode"
    override val dock_uploading = "Uploading…"
    override val dock_analyzing = "Analyzing…"
    override val dock_uploadFailed = "Upload failed — retake"
    override val dock_saved = "Saved"
    override val dock_needsReview = "Needs review"
    override val dock_noTracking = "(no tracking #)"
    override val dock_cameraPermission = "Camera permission required. Enable it in Settings and come back."
    override fun dock_closeBatchPrompt(itemCount: Int, needsReviewCount: Int) = buildString {
        append("$itemCount items")
        if (needsReviewCount > 0) append(", $needsReviewCount need review")
        append(". Close this batch?")
    }
    override val dock_selectWarehouseFirst = "Select a warehouse first"
    override val dock_photoProcessingFailed = "Photo processing failed — retake"
    override val dock_trackingNotRecognized = "Couldn't read the tracking number — retake (hold steady, get closer) or enter it manually"
    override fun dock_batchClosed(number: String) = "$number closed"

    override val batch_empty = "No items in this batch"
    override val batch_noTracking = "(no tracking #)"
    override val batch_needsReview = "Needs review"

    override val report_title = "Receive Report"
    override val report_empty = "No receipts in the last 3 days"
    override val report_today = "Today"
    override val report_yesterday = "Yesterday"
    override fun report_daySummary(batches: Int, items: Int) = "$batches batches · $items items"
}
