# 逐章检查与确认 API（P0-06）

均需登录，同源 Cookie/CSRF，校验章节、草稿和来源的用户归属。不存在或越权统一返回 `NOT_FOUND`。写请求沿用 [写入协议](mutations-and-sync.md)，在用户锁、业务更新、revision 和收据的同一事务内执行。

## 读取

- `GET /api/chapters/{id}/review`：章节 `id/name/version/checkStatus/abandoned/currentDraftId`，全部草稿摘要 `items`、正式映射 `cards: [{id,draftId}]`、`activeCount/flaggedCount/userCountDelta`、调整记录 `history`。摘要含 `parentDraftId`；排除项仍可核对。
- `GET /api/drafts/{id}`：完整当前内容、不可覆盖的 `originalContent`、原始 metadata、全部来源范围、追问及拆分父条目。沿用 P0-05 路径。
- `GET /api/chapters/{id}/cards`：本章已确认的完整正式卡片，`id` 为稳定正式 ID，含 `draftId/question/contentBlocks/originalContent/flags/sources/followups/sourceId/version`。未确认章节返回空数组。本阶段提供章节阅读入口，检索、笔记、收藏与学习另由后续任务交付。
- 原 `GET /api/import-jobs/{id}/chapters` 增加 `abandoned/checkedAt/activeCount/version`；`flaggedCount` 只计有效项。`candidateCount` 为包括历史排除项和拆分新增项在内的草稿行数。原 `job.report` 是不可变解析报告，不因人工调整重算。

章节及正式卡片组合读取使用 PostgreSQL 可重复读快照。

## 写入

`POST /api/chapters/{id}/review`，JSON 示例：

```json
{
  "operationId": "UUID",
  "dataEpoch": "UUID",
  "expectedVersion": 3,
  "action": "EDIT",
  "payload": {
    "draftId": "UUID",
    "reason": "对照 PDF 校正标题和来源边界",
    "value": {
      "question": "修正后的完整问题",
      "contentBlocks": [{"type":"MANUAL_TEXT","page":10,"textSpans":[{"text":"完整答案","bbox":[]}],"images":[]}],
      "flags": [{"kind":"MANUAL_CHECK","reason":"图片仍需核对"}],
      "sources": [{"page":10,"bbox":[0,30,600,800]}],
      "followups": [{"title":"追加追问","page":10,"text":"完整追问答案"}]
    }
  }
}
```

`expectedVersion` 是**章节版本**，覆盖本章编辑、数量调整、检查位置和确认，避免其他设备根据旧清单确认。服务端成功返回 `{chapter: review快照, mutation: 标准收据字段}`；`entityVersion` 等于本章新版本。浏览器先将完整 payload 写入 IndexedDB，再发送；收到匹配章节、操作 ID、代次和版本的确认才显示已同步。

| action | payload | 行为 |
| --- | --- | --- |
| `EDIT` | `draftId,value,reason` | 更新问题、内容、追问、标记和来源范围；若已导入则原位修正正式卡片及其关联来源/追问 |
| `MERGE` | `draftIds:[UUID,...],reason` | 同章 2～50 个不同有效草稿，首项作为目标，按传入次序串接全部内容、原始证据、来源和追问；其余项排除；数量变化 `1-n` |
| `SPLIT` | `draftId,parts:[value,...],reason` | 2～20 份完整结果；首份保留原草稿 ID，后续新增草稿并关联原条目；数量变化 `n-1` |
| `EXCLUDE` | `draftId,reason` | 标记有效草稿排除，保留全文、来源及历史；数量变化 `-1` |
| `PROGRESS` | `draftId` | 保存同章检查位置，章节转为检查中；已导入章节保持已导入 |
| `CONFIRM` | `allowFlagged:boolean` | 同事务生成本章所有有效正式卡片、全部来源、追问、拆分父卡关系及检查状态 |
| `ABANDON` | `confirmed:true,reason` | 显式放弃**本章**剩余草稿，逐项排除、记录原因/总数量变化并将本章关闭；其他章节和所有正式内容保留 |

`EDIT/SPLIT/MERGE/EXCLUDE` 同时记录操作后的检查位置。暂时离开页面没有放弃语义，已保存内容与检查位置保留。已关闭章节可阅读；已导入章节仅允许 `EDIT/PROGRESS`（重复 `CONFIRM` 返回原映射），禁止通过结构操作删除/重建正式内容。

确认前按 `activeCount/flaggedCount` 展示影响；有标记时 `allowFlagged` 必须为真，正式卡片保留 `needs_check` 和具体标记。空章拒绝确认。`draftId → cardId` 有唯一约束；同一操作重复返回原收据，不同操作再次/并发确认返回已有映射，不增加 revision 或卡片。

所有人工更新的内容块由服务端标为 `MANUAL_TEXT`。原始 PDF、提取证据和 `originalContent` 不被编辑覆盖；合并串接原始证据，拆分结果继承原证据。每次编辑/调整均保存操作前完整内容、来源和追问到 `draft_edit_operation.evidence`，历史摘要公开输入/输出 ID、原因及数量变化。正式修正保留卡片 ID、原始证据、复习状态和学习事件，仅递增展示修订及版本。

## 校验及错误

- 原因必填，最多 2,000 字；问题非空，最多 2,000 字；每个编辑结果 JSON 最多 2,000,000 字符。
- 内容块、标记、来源和追问必须为数组；至少一个内容块及一个来源，最多 1,000 个来源范围。
- 来源页为该用户原 PDF 范围内的整数；bbox 为四个有限非负数，右大于左、下大于上，保存的页面证据存在时也校验页面尺寸。坐标支持 PDF 原始小数精度。
- 内容块必须有文本数组和图片数组，页码对应至少一个来源；追问标题非空且页码有效。编辑不接受替换来源文档 ID、原始证据或卡片 ID。
- `VERSION_CONFLICT`：返回服务端本章 `current`；编辑冲突另含 `serverDraft` 全文，浏览器保留本地完整 payload，用户可下载双方核对副本，再明确重新保存编辑或放弃本地操作。结构调整和确认冲突须刷新后重新选择。
- `CHECK_REQUIRED`：未明确允许带标记导入，附 `flaggedCount`。
- `EMPTY_CHAPTER`：没有有效草稿。
- `CHAPTER_CLOSED`：已排除条目、已放弃章节或不允许的正式章节结构操作。
- `EPOCH_EXPIRED/OPERATION_ID_REUSED/CSRF_INVALID` 沿用统一协议；旧代次不自动重放。

确认失败后卡片、来源、追问、章节状态、revision 和收据全部回滚，可以使用原操作重试。
