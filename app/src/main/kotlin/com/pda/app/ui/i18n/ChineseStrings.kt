package com.pda.app.ui.i18n

object ChineseStrings : AppStrings {
    override val common_retry = "重试"
    override val common_cancel = "取消"
    override val common_back = "返回"
    override val common_loading = "加载中…"
    override fun itemCount(n: Int) = "$n 件"

    override val login_subtitle = "仓库管理系统"
    override val login_username = "用户名"
    override val login_password = "密码"
    override val login_showPassword = "显示密码"
    override val login_hidePassword = "隐藏密码"
    override val login_rememberCredentials = "记住账号密码"
    override val login_loginButton = "登 录"
    override val login_language = "语言"

    override val home_selectWarehouse = "选择仓库"
    override val home_switchWarehouse = "切换仓库"
    override fun home_greeting(name: String) = "你好，$name"
    override val home_selectWarehouseFirst = "请先选择仓库"
    override val home_noWarehouses = "暂无可用仓库"
    override val home_dockReceive = "Dock 收货"
    override val home_receiveReport = "收货报表"
    override val home_logout = "退出登录"

    override val dock_title = "Dock 收货"
    override fun dock_batchTitle(number: String) = "批次 $number"
    override val dock_inputMethod = "录入方式"
    override val dock_inputMethodPicture = "拍照"
    override val dock_inputMethodBarcode = "扫码"
    override val dock_startBatch = "开始批次"
    override val dock_closeBatch = "关闭批次"
    override val dock_close = "关闭"
    override val dock_confirm = "确认"
    override val dock_scanHint = "扫描或输入运单号…"
    override val dock_trackingLabel = "运单号"
    override val dock_carrier = "承运商"
    override val dock_condition = "状态"
    override val dock_uploading = "上传中…"
    override val dock_analyzing = "识别中…"
    override val dock_uploadFailed = "上传失败 — 请重拍"
    override val dock_saved = "已保存"
    override val dock_needsReview = "需复核"
    override val dock_noTracking = "（无单号）"
    override val dock_cameraPermission = "需要相机权限。请在系统设置中开启后返回。"
    override fun dock_closeBatchPrompt(itemCount: Int, needsReviewCount: Int) = buildString {
        append("共 $itemCount 件")
        if (needsReviewCount > 0) append("，其中 $needsReviewCount 件需复核")
        append("。确定关闭此批次？")
    }
    override val dock_selectWarehouseFirst = "请先选择仓库"
    override val dock_photoProcessingFailed = "照片处理失败 — 请重拍"
    override fun dock_batchClosed(number: String) = "$number 已关闭"

    override val batch_empty = "该批次暂无明细"
    override val batch_noTracking = "（无单号）"
    override val batch_needsReview = "需复核"

    override val report_title = "收货报表"
    override val report_empty = "最近三天无收货"
    override val report_today = "今天"
    override val report_yesterday = "昨天"
    override fun report_daySummary(batches: Int, items: Int) = "$batches 批 · $items 件"
}
