package com.pda.app.ui.i18n

object SpanishStrings : AppStrings {
    override val common_retry = "Reintentar"
    override val common_cancel = "Cancelar"
    override val common_back = "Atrás"
    override val common_loading = "Cargando…"
    override fun itemCount(n: Int) = "$n artículos"

    override val login_subtitle = "Sistema de Gestión de Almacén"
    override val login_username = "Usuario"
    override val login_password = "Contraseña"
    override val login_showPassword = "Mostrar contraseña"
    override val login_hidePassword = "Ocultar contraseña"
    override val login_rememberCredentials = "Recordar credenciales"
    override val login_loginButton = "Iniciar Sesión"
    override val login_language = "Idioma"

    override val home_selectWarehouse = "Seleccionar almacén"
    override val home_switchWarehouse = "Cambiar almacén"
    override fun home_greeting(name: String) = "Hola, $name"
    override val home_selectWarehouseFirst = "Seleccione un almacén primero"
    override val home_noWarehouses = "No hay almacenes disponibles"
    override val home_dockReceive = "Recepción"
    override val home_receiveReport = "Informe de Recepción"
    override val home_logout = "Cerrar sesión"

    override val dock_title = "Recepción"
    override fun dock_batchTitle(number: String) = "Lote $number"
    override val dock_inputMethod = "Método de Entrada"
    override val dock_inputMethodPicture = "Foto"
    override val dock_inputMethodBarcode = "Escaneo"
    override val dock_startBatch = "Iniciar Lote"
    override val dock_closeBatch = "Cerrar Lote"
    override val dock_close = "Cerrar"
    override val dock_confirm = "Confirmar"
    override val dock_scanHint = "Escanear o ingresar # de rastreo…"
    override val dock_trackingLabel = "# de Rastreo"
    override val dock_carrier = "Transportista"
    override val dock_condition = "Condición"
    override val dock_uploading = "Subiendo…"
    override val dock_analyzing = "Analizando…"
    override val dock_uploadFailed = "Error al subir — reintentar"
    override val dock_saved = "Guardado"
    override val dock_needsReview = "Requiere revisión"
    override val dock_noTracking = "(sin # de rastreo)"
    override val dock_cameraPermission = "Se requiere permiso de cámara. Actívelo en Ajustes y vuelva."
    override fun dock_closeBatchPrompt(itemCount: Int, needsReviewCount: Int) = buildString {
        append("$itemCount artículos")
        if (needsReviewCount > 0) append(", $needsReviewCount requieren revisión")
        append(". ¿Cerrar este lote?")
    }
    override val dock_selectWarehouseFirst = "Seleccione un almacén primero"
    override val dock_photoProcessingFailed = "Error al procesar la foto — reintentar"
    override fun dock_batchClosed(number: String) = "$number cerrado"

    override val batch_empty = "No hay artículos en este lote"
    override val batch_noTracking = "(sin # de rastreo)"
    override val batch_needsReview = "Requiere revisión"

    override val report_title = "Informe de Recepción"
    override val report_empty = "Sin recepciones en los últimos 3 días"
    override val report_today = "Hoy"
    override val report_yesterday = "Ayer"
    override fun report_daySummary(batches: Int, items: Int) = "$batches lotes · $items artículos"
}
