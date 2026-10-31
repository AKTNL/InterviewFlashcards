# PDF 解析任务与草稿 API（P0-05）

沿用统一响应、会话、CSRF 和错误格式。以下接口均要求登录，只能读取当前用户所属的任务、章节、草稿或来源。未知和越权 ID 返回 `404 NOT_FOUND`。

## 上传与任务

`POST /api/sources` 保持 multipart 文件及 `operationId`、`dataEpoch`。新增返回字段 `data.importJobId`；来源登记、唯一任务和上传收据在同一事务保存。同用户同指纹文件始终关联已有任务；上传重放收据可能是任务早期状态，应使用任务查询取得当前进度。升级 V4 时为历史来源补建缺失任务。

- `GET /api/import-jobs?sourceId={uuid}`：当前用户的任务列表，来源筛选可省略；按创建时间倒序。
- `GET /api/import-jobs/{id}`：单任务。
- `GET /api/import-jobs/{id}/chapters`：该任务已生成的章节，按模块/目录顺序；尚未生成时为空数组。

任务字段：`id`、`sourceId`、`status`、`stage`、`progress`（0～100）、`version`、`parserVersion`、`errorCode`、`errorMessage`、`deckId`、`report`、`updatedAt`。不暴露文件存储键或原始提取证据。

状态流：`QUEUED → PARSING → READY`；错误进入 `FAILED`。阶段为 `WAITING`、`EXTRACTING`、`ANALYZING`、`PERSISTING`、`COMPLETE`。进度为阶段估值，不表示剩余时间。页面轮询进行中的任务，并在窗口重新激活时刷新；任务在后端独立运行，离开页面不终止。

`READY` 的报告包含物理页数、目录主问题数、草稿数、当前资料基线数、识别差异、基线差异、模块数和逐章计数、缺失基线章节。逐章记录 `name`、`expectedCount`、`tocCount`、`candidateCount`、`recognitionDifference`（草稿减目录）、`baselineDifference`（草稿减基线）。基线仅用于核对，不生成或补造候选。所有章节/草稿原子提交，数量不符保留差异报告；边界无法定位或乱序时明确失败，不返回部分成功。

章节字段：`id`、`name`、`originalName`、`module`、`sortOrder`、`checkStatus`、`version`、`candidateCount`、`flaggedCount`。例如 `Redis` 同时保留 `Reids篇`。

## 重试

`POST /api/import-jobs/{id}/retry`，JSON：

```json
{
  "operationId": "操作 UUID",
  "dataEpoch": "当前用户代次 UUID",
  "expectedVersion": 12
}
```

返回 `{job, mutation}`。遵守 P0-04 的事务收据、摘要、代次与版本协议；响应丢失使用原操作查询收据，不根据已经变化的任务版本生成新请求。前端将重试请求保存在同一 IndexedDB 队列并展示同步状态。

仅 `FAILED` 任务可重试；已有任何草稿时拒绝重新解析，保留人工编辑和已确认卡片。`READY` 不提供重新解析操作。重试保留原任务 ID；存在提取证据时从分析阶段继续，没有证据时重新提取。任务认领生成新的执行令牌，旧线程不能提交新尝试或旧代次的结果。

| 错误 | 行为 |
| --- | --- |
| `VERSION_CONFLICT` | 返回 `details.current`，刷新后核对，不自动覆盖 |
| `EPOCH_EXPIRED` | 保留旧操作供核对，不重放 |
| `IMPORT_NOT_RETRYABLE` | 任务不是失败状态，刷新任务 |
| `IMPORT_CONTENT_EXISTS` | 已有草稿，禁止重解析覆盖 |

任务失败原因存放于 `errorCode/errorMessage`：`INTERRUPTED`（重启中断，可明确重试）、`INSUFFICIENT_TEXT`（文本不足/扫描件）、`UNSUPPORTED_TOC`、`UNSUPPORTED_ROTATION`、`BOUNDARY_UNCERTAIN`、`IMPORT_FAILED`（来源读取或保存失败）。界面提供回到资料页重新选择 PDF 的入口；重复上传同文件仍定位原任务，需要显式重试。

## 草稿与来源

- `GET /api/chapters/{id}/drafts?offset=0&limit=20`：`{items,total,offset,limit}`。offset ≥ 0，limit 1～50；无效范围返回 400。条目包含 `id/question/sourceEntry/flags/version/excluded`，不批量返回正文。
- `GET /api/drafts/{id}`：按需返回全文。包含 `chapterId/sourceId/question/sourceEntry/contentBlocks/originalContent/metadata/flags/sources/followups/version/excluded`。
- `GET /api/sources/{id}/pages/{page}/image`：鉴权、禁止缓存的 PNG，110 DPI、完整物理页；非法页码返回 404。使用 PDFBox 从不可变来源按需渲染，包含代码、表格和图片，也可能含相邻题内容。
- 原 PDF：`/api/sources/{id}/content#page={physicalPage}`，保留 Range/HEAD 支持。页面片段由阅读器处理，不发送给服务器。

`contentBlocks/originalContent` 为逐页 `SOURCE_TEXT`，保留全部有序 `textSpans`（文字、坐标、字体及风险证据）与 `images` 区域；首次生成两者相同，后续编辑不得覆盖原始证据。`sources` 为逐页 `{page,bbox}`，bbox 使用左上角 point 坐标 `[x0,y0,x1,y1]`，同页题界采用半开区间。`metadata` 保存目录页/链接目标、起止边界、正文 SHA-256 和解析器版本；`sourceEntry` 为任务内稳定目录条目（如 `q001`）。

追问同时保留在连续完整答案及 `followups` 中；追问字段包含 `title/page/bbox/text`，text 为从追加标题到下一追问/本题终点的完整文本。它们不生成独立学习卡片。

异常标记包含种类、原因、可选页码/区域与证据，涉及字体映射、特殊字符、来源图片、空白页以及代码/表格结构需核对。所有候选保留 `LAYOUT_REQUIRES_SOURCE`，不声称已重建复杂语义。字体异常映射用 `unicodeCodePoints` 无损保存，避免 PostgreSQL JSONB 不支持 U+0000；不据此猜测缺失数字。

草稿不写入正式 `card`、复习状态或学习事件。编辑、合并、拆分和章节确认属于 P0-06。
