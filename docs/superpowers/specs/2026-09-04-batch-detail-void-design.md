# Batch Detail — 作废 + customerName

- 日期：2026-09-04
- 范围：批次详情页（Receive Report → 批次明细）每行显示客户名，并支持作废
- 状态：已与用户确认（方案 A）

## 已确认决策

| 项 | 选择 |
|---|---|
| 作废成功后 | 从列表移除；列表空则 Empty |
| 确认 | AlertDialog 确认后再调 API |
| 范围 | 仅批次详情页；不改拍照页列表 |
| API | `POST /api/receiving-items/{id}/void`（RMA 已有） |

## 不做

- un-void
- 拍照页 / Dock 当前批次列表作废
- 按 status 过滤 getItems（成功后本地移除即可）

## UI

- 行：tracking → carrier → customerName（空不显示）
- 右侧红色「作废」；needsReview 与按钮并排
- 确认文案对齐网页语义；中/英/西 i18n

## 数据流

ViewModel `voidItem(id)` → Repository → API；Loading 禁用该行；Success 本地删行；Error 中文短提示。
