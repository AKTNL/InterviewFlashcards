# P0-07：正式题库、个人整理与来源阅读

所有 `/api/cards/**` 请求要求登录并按用户归属查询。读请求不创建学习事件、不修改修订号或复习日期。响应沿用 `{ data, meta }`；写入沿用 P0-04 的 CSRF、幂等收据、代次和乐观锁协议。

## 读取

| 接口 | 含义 |
| --- | --- |
| `GET /api/cards/catalog` | 题库、模块、章节树的扁平条目：各级 ID/名称、章节 `checkStatus` 与正式 `cardCount`；保留待确认章节，草稿不计入正式题数 |
| `GET /api/cards` | 正式卡片摘要、`total/offset/limit/learningDate`；默认每页 20 条，最多 100 条 |
| `GET /api/cards/{id}` | 问题、章节、版本、标记、全文与原始证据、追问、全部来源、个人整理、技术纠错、版本与标签、现有复习状态 |

列表可组合 `deckId/moduleId/chapterId`（UUID）、`q`（最多 200 字符，题目字面子串，不区分大小写）、`filter`、`offset>=0` 和 `1<=limit<=100`。`%`、`_`、`!` 作为普通关键词字符。按题库、模块、章节、原草稿排序和稳定 ID 排序；未确认草稿始终不会进入结果。无结果时 `items=[]`、`total=0`，无权范围不会泄漏题目。

| filter | 口径 |
| --- | --- |
| `ALL` | 全部正式题目 |
| `UNLEARNED` / `LEARNED` | 无 / 有当前 `review_state`；评分工作流由 P0-08 建立 |
| `DUE` | 当前复习日期 `<=` 服务端上海学习日；此处为题库查询，学习范围及每日任务另由 P0-09 处理 |
| `WEAK` | 最近评分为 `FORGOT` 或 `VAGUE` |
| `FAVORITE` | 个人收藏为 true |
| `CHECK` | 正式卡片 `needsCheck=true` |

详情中的 `version` 是正式卡片内容/元数据版本，`chapterVersion` 用于既有章节内容修改，`annotation.version` 独立保护笔记/提示/要点/收藏。没有个人整理时返回空文本、`favorite=false, version=0`；未知 `applicableVersion=null`，不推断技术适用版本。

来源数组每段返回 `sourceId/name/page/bbox`，`page` 是从 1 开始的 PDF 物理页码，bbox 为左上角 `[left,top,right,bottom]`。跨页及同页多段分别保留。详情中的 `originalContent` 永不被个人修改覆盖；`SOURCE_TEXT` 表示提取文本、`MANUAL_TEXT` 表示人工展示修订；`technicalCorrection` 是单独标明的个人技术纠错。

## 写入

`POST /api/cards/{id}`：

```json
{
  "operationId": "UUID",
  "dataEpoch": "UUID",
  "expectedVersion": 0,
  "action": "ANNOTATION",
  "payload": { "notes": "自己的表达", "hint": "回忆提示", "memoryPoints": "记忆要点", "favorite": true }
}
```

`ANNOTATION` 是完整个人整理替换，必须提供四个字段；文本限制分别为 50000、10000、20000 字符，收藏必须是布尔值；`expectedVersion` 使用 `annotation.version`。收藏按钮同样带上现有笔记等文本，避免覆盖个人整理。

`METADATA` 的 payload：

```json
{ "technicalCorrection": "个人核对后的技术说明", "applicableVersion": null, "tags": ["Java", "集合"] }
```

技术纠错最多 50000 字符，适用版本最多 200 字符，空白转为 null；标签最多 30 个、每个非空且最多 100 字符，去掉首尾空白并去重。`expectedVersion` 使用正式卡片 `version`。

成功返回 `{card:{id,version}, detail, mutation}`，这里 `card.version`/`mutation.entityVersion` 是本次修改对象（整理或元数据）的版本，完整详情中仍分别保留两种版本。重复操作返回原收据；同 ID 不同内容返回 `OPERATION_ID_REUSED`。旧版本返回 `VERSION_CONFLICT`，`details.current` 包含服务端全文供核对；旧代次返回 `EPOCH_EXPIRED`。无权 ID 返回 404，未登录 401，缺 CSRF 403，无效参数/字段 400。

正文、问题、追问、来源和待检查标记沿用 `POST /api/chapters/{id}/review` 的 `EDIT`，附 `chapterVersion`、draftId、完整 value 与调整原因。这样草稿证据、正式卡片和审计记录在同一事务中一致更新。既有正式 ID、父卡关系、原始证据、笔记、技术纠错及复习记录保留。

所有写入先经 IndexedDB 队列持久化，服务端确认前显示待同步；响应丢失先查收据。版本冲突保留本地 payload 与服务端详情，可下载核对副本，再明确使用本地内容重提；不自动覆盖。

## 阅读入口

`/cards` 为全题库，`/chapters/{id}/cards` 兼容已有章节入口，`/cards/{id}` 为详情。答案和提示默认隐藏，答案一键展开全文，各追问独立展开；持续显示待检查提醒，详细原因可展开。代码/表格提取文本在内容区滚动；图片通过鉴权页面原图呈现并显示来源，失败时提供原 PDF。

`/sources/{id}/read?page=10&bbox=0,20,300,400&card={cardId}` 使用本地 PDF.js 与 worker，提供物理页跳转、前后页、适屏/放大、范围高亮及原 PDF 新窗口兜底。cMap、标准字体和 wasm 同版本本地部署，不依赖 CDN。来源文件仍走既有鉴权 Range 接口。bbox 可省略；错误页码限制在真实总页数内。
