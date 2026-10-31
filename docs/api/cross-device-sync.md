# P0-10 跨设备同步与评分冲突

继承[写入协议](mutations-and-sync.md)、[学习评分](learning.md)及[每日计划](daily-learning.md)。所有接口需登录；写请求校验 CSRF。归属、代次、版本、操作摘要、业务变更和收据仍在用户锁与同一事务中处理。

## 修订与快照

`GET /api/sync/revision` 返回 `dataEpoch/revision/learningDate`。日期由服务端上海学习时区决定，即使修订不变，跨日也会刷新视图。

`GET /api/sync/snapshot` 返回相同头信息、既有 `sources` 和新增 `data`。`data` 的键为下列表名，每项为该用户完整实体数组，字段沿用数据库 snake_case；JSONB 保持对象/数组，时间戳为 ISO 8601。它是权威全量替换快照，缺失项表示应清理旧项；不把快照合并进旧代次状态。

- 题库、内容和资源：`deck/module/chapter/card/asset/source_span/follow_up/tag/card_tag`。
- 导入：`import_job/draft_card/draft_edit_operation/chapter_check_progress`。
- 整理：`personal_annotation`，正式卡片中的纠错和待检查字段。
- 学习：`review_state/review_event/learning_session/session_item/review_replay_allowance`。
- 偏好：`learning_preference/learning_preference_chapter`。
- 冲突：`sync_conflict`，包含已解决记录及其版本与解决方式。

不包含账号/密码、Cookie、存储键、解析器 attempt token、内部 raw evidence 或文件二进制。来源 PDF、图片与页面原图继续通过已有鉴权接口按需读取。全部查询持有用户共享锁，与 revision 一致；后台导入原有写入也推进用户修订。

登录、重新联网、页面激活、写入成功和手动刷新检查服务端。可见页面每 30 秒检查轻量修订；没有变化则不重新下载完整快照。无待提交操作的读取失败按 5/10/20/40/80/120 秒退避；待提交操作复用原队列退避和收据查询。登录失效停止提交，保留 IndexedDB 内容，重新登录同用户后继续；不同用户和旧代次保持隔离。页面收到新修订或新学习日后刷新读取；笔记、草稿和设置编辑中的内容保留，保存仍核对原版本。

## 同状态评分

评分继续使用 `POST /api/learning-sessions/{id}/review`，绑定原会话、条目、会话版本及 `expectedReviewVersion`。若该条目的原复习版本已被一个有效 RATE 推进：

| 情况 | 响应和持久化 |
| --- | --- |
| 评分相同 | 成功收据含 `duplicateScore: true`、已有 `eventId`、当前 dueDate 和最新 `session`；不新增有效事件或推进复习/用户 revision |
| 评分不同 | 成功收据含 `conflict` 和最新 `session`；只新增 `sync_conflict` 并推进 revision，不改评分、日期或学习量 |
| 找不到可核对的原有效评分，如已经撤销 | 原版本冲突，保留本地提交供核对 |

候选保存成功不等于评分已生效：前端将此收据标为“待处理”，不显示“已同步”评分。候选、原事件和双方内容均保存在服务端，其他设备快照可读取。响应丢失仍先找原操作收据，不再创建第二个候选。手动与每日会话均跳过已被其他设备推进的原条目；额外练习必须明确新进入。

`conflict` 结构：`id/cardId/dataEpoch/operationId/version/resolved/resolution/local/server`。`local` 含原 `sessionId/payload`；`server` 含 `eventId/rating/learningDate/afterState/current`，保留冲突发生时证据。最新安排通过卡片详情核对，卡片 `review.version` 为持久递增的复习版本。

## 明确解决评分冲突

`POST /api/sync/conflicts/{id}/resolve`：

```json
{
  "operationId": "新的UUID",
  "dataEpoch": "当前UUID",
  "expectedVersion": 0,
  "expectedReviewVersion": 1,
  "choice": "CANDIDATE"
}
```

- `CURRENT`：接受服务端现状，解决候选，不改学习事件、日期或进度。即使后续已学习或跨日，仍可放弃候选。
- `CANDIDATE`：仅当原评分仍为该卡片最新复习版本、当前版本匹配且仍在同一服务端学习日，才允许更正；否则返回 `VERSION_CONFLICT` 和最新状态，不覆盖后续学习。
- 更正增加 `CORRECT` 事件，以 `undo_of` 关联并替代原事件，原事件仍保留；从原评分前状态和原评分时间重新计算安排，保留首次学习归属，不按点击更正的时间再练一次。有效统计包含 RATE 和 CORRECT，排除被撤销或替代事件。
- 更正同步调整尚未处理的原会话重练安排；已发生后续评分的卡片不能更正。更正记录不能通过普通评分撤销入口回滚；如需继续巩固，显式进入新练习。
- 返回 `{conflict, mutation}`，`entityVersion` 为冲突解决后版本。相同操作重试返回原收据；并发解决、旧版本、旧代次及越权均被拒绝。另一设备刷新后自动解除该候选在本地队列中的阻塞。

编辑冲突仍保留本地完整负载与服务端全文，支持下载副本、接受服务端或核对后使用新版本/新操作 ID 保存本地值。笔记、草稿 EDIT、来源名称和偏好可显式重新保存；结构调整须重新检查选择。重新保存前若服务端又有新版本，先更新核对内容，要求再次核对，不直接覆盖未展示的新内容。

同步总状态综合未确认队列、失败、待处理候选和读取错误；一次成功读取不会清除待处理状态。`SESSION_EXPIRED` 归为待处理，禁止自动创建新练习或改版本重放。
