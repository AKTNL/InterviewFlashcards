# 题库删除与进度重置（P0-12）

所有接口要求登录；写入要求 CSRF。操作范围固定为当前用户的单个题库。

| 接口 | 用途 |
| --- | --- |
| `GET /api/deck-data` | 返回全部题库 `id/name`，包括仅有草稿的题库 |
| `GET /api/deck-data/{id}/preview` | 读取题库名称、`dataEpoch/revision` 和影响计数 |
| `POST /api/deck-data/{id}` | 明确确认后删除或重置 |

预览 `counts`：`cards` 正式卡片、`drafts` 草稿、`annotations` 笔记/提示/收藏行、`learned` 已学卡片、`events` 全部学习事件（含撤销/更正）、`sessions` 含相关卡片的不同会话。

写入体：

```json
{
  "operationId": "UUID",
  "dataEpoch": "预览代次 UUID",
  "expectedRevision": 42,
  "action": "RESET",
  "confirmed": true
}
```

- `RESET`：清除本题库评分/撤销/更正/重练事件、复习状态、评分候选及会话条目。卡片回到未学，之后重新计为新题；保留正式内容、草稿、来源、个人整理、收藏、标签与学习偏好。
- `DELETE`：再移除本题库内容、草稿、检查/调整记录、个人整理及学习范围关联。原 PDF、图片和提取证据保留；资料中的解析任务转为 `FAILED/DECK_DELETED`，可显式重试重新生成题库。账号、其他题库和完整备份任务保留。
- 含目标题库的会话整体标为 `INVALIDATED`、递增版本，包括混合题库批次。其他题库的条目和历史仍保留，旧批次不能继续评分；重新生成每日计划或从题库进入新批次。

成功返回 `{action, impact, mutation}`，`impact` 为实际核对的预览，`mutation.entityVersion=0`。同一操作 ID/内容返回原收据；响应丢失后先查收据，不重复删除或重置。预览后任意用户业务修订变化返回 `409 DATA_PREVIEW_STALE`，必须重新预览和确认；不自动换版本重放。恢复后的旧代次返回 `409 EPOCH_EXPIRED`，无归属题库返回 `404 NOT_FOUND`，未明确确认返回 `400`。

同用户写锁内完成核对、清除、会话失效、版本递增与收据写入，事务异常整体回滚。保持代次，递增用户修订；其他设备通过现有全范围刷新获取移除后的快照。旧评分即使携带新会话版本也不能写入已失效会话；旧离线操作保留供核对。前端先保存到 IndexedDB，未获得有效收据只显示待确认；预览过期的操作在同步面板放弃后重新进入数据操作。

单题“重新学习”沿用 `POST /api/learning-sessions/{id}/review` 的 `RELEARN`，确认中显示题目、原复习日期与今日日期。它保留历史和首次学习信息，不重新计为新题；具体规则见 [学习 API](learning.md)。
