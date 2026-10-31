# P0-09：每日计划、学习偏好与会话恢复

全部接口要求登录。写请求使用 CSRF、`operationId/dataEpoch`、用户锁和同事务收据；响应的 `mutation` 沿用[统一写入协议](mutations-and-sync.md)。偏好、正式内容、复习状态使用各自版本，修改偏好不改历史事件或 dueDate。

## 学习设置

`GET /api/preferences` 返回：

```json
{"saved":false,"version":0,"dailyNewCount":5,"reviewBatchSize":20,"referenceMinutes":null,"chapterIds":[]}
```

未首次保存时只返回可修改的建议值，不自动写库或选中章节。保存后 `saved=true`，返回实际版本与范围。

`POST /api/preferences`：

```json
{
  "operationId":"UUID", "dataEpoch":"UUID", "expectedVersion":0,
  "dailyNewCount":5, "reviewBatchSize":20, "referenceMinutes":null,
  "chapterIds":["已确认章节 UUID"]
}
```

- `dailyNewCount`：整数 0～100；`reviewBatchSize`：整数 1～100；`referenceMinutes`：null 或整数 1～1440。
- `chapterIds`：0～1000 个不重复 UUID，必须是当前用户的 IMPORTED 章节；空数组暂停全部每日任务，仍可在题库自主练习。
- 首次保存 `expectedVersion=0`，成功后版本为 1；后续严格乐观锁。响应为 `preferences/mutation`；重复操作返回原收据，版本不再次增加。
- 设置保存推进当日自动计划的会话版本，拒绝设备上仍基于旧设置的评分；手动题库练习不受学习范围限制。
- `VERSION_CONFLICT` 包含 `details.current` 的服务端偏好。本地设置保留在 IndexedDB，可下载两侧副本；核对并放弃旧待同步操作后重新读取、编辑和保存，不自动覆盖。

## 首页摘要

`GET /api/today` 返回同一数据库读取快照中的：

| 字段 | 含义 |
| --- | --- |
| learningDate / learningTimezone | 服务端上海学习日 / 时区，设备时区不参与计算 |
| preferences | 当前设置及是否已首次保存 |
| summary.learned / todayNew / todayReview | 总已学、今日不同新学卡片数、今日不同复习卡片数；口径同[评分接口](learning.md) |
| remainingNew | `max(0, dailyNewCount - summary.todayNew)`；今日新学统计包含其他范围/自主练习产生的有效首次评分 |
| availableNew | 选中且已确认章节内的未学卡片数 |
| dueTotal | 范围内 `dueDate <= learningDate` 的全部已学卡片数，不受批量截断 |
| chapters | 全部已确认章节的 `id/name/moduleName/deckName/selected/total/learned/due/weak`，暂停章节仍显示原到期和薄弱数 |
| continuing | 当日仍有有效剩余项的 `id/mode/remaining`；含每日计划及题库手动练习，按建立时间倒序 |

薄弱表示当前最近评分为不会或模糊，收藏不参与调度。初次无章节、范围为空、无新题或无到期题均可正常读取。

## 建立或继续每日计划

`POST /api/learning-sessions/daily`：

```json
{"operationId":"UUID","dataEpoch":"UUID","mode":"MIXED"}
```

`mode` 为 MIXED（到期优先再新题）、REVIEW（只复习）、NEW（只学新题）。必须先显式保存设置。响应为 `session/mutation`，创建版本为 0。同模式、同日已有有效未完成会话时返回原会话，不重复创建队列；复用不推进 revision。无条目时返回正常完成的空轮。

新轮复习截取 `reviewBatchSize` 张：先 dueDate 升序，再不会、模糊、记住了，最后稳定卡片 ID。新题最多截取当日剩余额度：按题库稳定 ID、模块/章节目录顺序、草稿目录顺序、稳定卡片 ID。一个混合轮最多 100 道复习加 100 道新题，另沿用原有有限自动重练。

## 恢复与有效队列

沿用 `GET /api/learning-sessions/{id}` 和评分写接口。新增返回：

- `mode`：MANUAL / MIXED / REVIEW / NEW。
- `items[].itemKind`：MANUAL / NEW / REVIEW / REPLAY。
- `items[].unavailable/skipReason`：当前已暂停、卡片不存在、复习版本已变、已不在到期集合或超出新题额度的条目；原队列保留，读取不改历史、不删除证据。
- `current`：首个未完成且当前有效的条目；`status` 按当前有效队列计算。已暂停条目不阻塞后续题；恢复范围或提高目标后，尚未评分且重新符合条件的原条目可恢复。
- `round.newCount/reviewCount/attention`：本会话有效新学/复习不同卡片数和仍需关注的 `id/question/latestRating/lapseCount`。撤销排除原事件与补偿行；同日首次学习后的重练不冒充复习或新增已学。

服务端保存会话、学习日和全部原始条目，不依赖浏览器滚动位置。每次读取和评分都重新核对有效队列；评分仍须匹配会话、条目与复习版本，不能自动换成下一题提交旧评分。跨设备已处理的条目自动跳过；同一会话旧版本写入仍返回可核对冲突。

返回首页即暂离本轮，首页“接着上次继续”恢复服务端剩余项。刷新后答案继续默认隐藏；仅本次点击创建计划的收据触发跳转，加载历史收据不会将首页带回旧练习。设置与建会话操作也进入 IndexedDB，丢响应先找原收据。

跨天旧会话保留已提交记录并只读，不进入今日继续列表。重新开始按新学习日计算额度和到期集合，不继承昨日未用额度，不修改原逾期日期。复习批量变化作用于后续新建轮，当前轮持续核对范围、额度和卡片状态；当前轮完成后可回首页开始下一批，结束文案不表示全部题目已经掌握。

错误沿用 401、CSRF 403、NOT_FOUND（含不属于用户或未确认的章节）、EPOCH_EXPIRED、VERSION_CONFLICT、OPERATION_ID_REUSED；字段范围/重复章节/未保存设置/非法模式返回 400。每日读取、浏览和展开答案不写学习状态。
