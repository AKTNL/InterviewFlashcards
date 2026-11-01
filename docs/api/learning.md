# P0-08：评分、调度、撤销与有限重练

所有接口要求登录，写请求要求 CSRF。业务写入沿用 [幂等协议](mutations-and-sync.md)：`operationId`、`dataEpoch`、请求摘要、用户锁、业务变更和收据在同一个数据库事务中完成。时间戳为 UTC；日期由服务端 `Asia/Shanghai` 学习日产生。

## 明确选择卡片建立练习

`POST /api/learning-sessions`

```json
{
  "operationId": "UUID",
  "dataEpoch": "UUID",
  "cardIds": ["正式卡片 UUID"]
}
```

1～100 张不重复卡片，逐项校验归属；按传入顺序保存会话条目及其复习版本。返回 `session` 和 `mutation`，创建版本为 0。重复操作返回原收据。创建会话、读取题目、展开答案和提示不产生评分，不改变调度及已学数。此入口为题库单题或本页练习；自动每日计划、范围设置和恢复见 [P0-09 API](daily-learning.md)。

## 会话与统计读取

`GET /api/learning-sessions/{id}` 返回：

- `id/version/status/learningDate/today/expired`；跨天后旧会话只读，不能提交评分、撤销或重新学习。
- `items`：有序的 `id/cardId/question/sortOrder/completed/skipped/isReplay/expectedVersion/currentVersion`。
- `current`：第一条未完成条目；本轮结束为 null。
- `review`：当前题的 `firstLearnedAt/lastReviewedAt/dueDate/stage/latestRating/lapseCount/version`；未学为 null。
- `events`：本会话有效评分及重新学习事件，按发生顺序倒序，含 `id/cardId/kind/rating/isReplay/afterState`。撤销的原事件和补偿行保留在数据库审计历史中，不混入有效结果。
- `canUndo`：最近有效事件属于本会话，会话与卡片复习版本均未被后续操作更改。
- `sameDayReplay`：当前卡片最近评分是否属于今日，用于区分正常提前练习与同日重练。

`GET /api/review-events/summary` 返回 `learningDate/learned/todayNew/todayReview`。统计根据有效 RATE 事件和当前状态重算，排除 UNDO、被撤销事件和 RELEARN。今日首次学习后的同日练习不计今日复习。此接口为 P0-09 首页和计划额度提供基础口径，范围统计使用 `/api/today`。

## 原子评分写入

`POST /api/learning-sessions/{id}/review`

```json
{
  "operationId": "UUID",
  "dataEpoch": "UUID",
  "expectedVersion": 0,
  "action": "RATE",
  "payload": {
    "itemId": "当前条目 UUID",
    "expectedReviewVersion": 0,
    "rating": "FORGOT",
    "answerRevealed": true,
    "confirmEarly": false
  }
}
```

`expectedVersion` 为会话版本；`expectedReviewVersion` 必须同时等于条目创建时的复习版本和卡片当前复习版本。未学卡片也有单调递增的复习版本，撤销首次评分后不会重用旧版本。服务器校验当前条目及版本，客户端只在展开完整答案后允许 RATE；客户端声明 `answerRevealed=true`，服务端拒绝缺少声明的评分。

`rating` 仅为 `FORGOT/VAGUE/REMEMBERED`。正常提前练习须 `confirmEarly=true`；同日重练不需要提前确认。结果包含 `eventId/dueDate/isReplay/session/mutation`。评分写入后更新状态、标记当前条目完成、必要时在队尾追加一次重练，再返回下一题；只有收到原收据确认才前进。

算法默认 `rules-v1`，成功间隔 `[1,3,7,14,30]`；stage 为 -1～4。不记得重置 -1 并增加一次遗忘；模糊降低一级且最低 -1；两者次日到期。记住了上升一级并使用新阶段间隔。所有间隔基于本次学习日，逾期题不按旧到期日累加。

同卡同学习日后续 RATE 均为重练：不变更 stage、遗忘次数和首次时间；记住了保持原 dueDate，不会/模糊取 `min(原 dueDate, 今日+1)`。最新评分用于薄弱筛选。自动重练由数据库唯一额度限制为每卡每日及每会话最多一次，重建会话不能绕过。自动条目再次不会不再追加。

## 跳过、撤销、重新学习

同一写端点的其他 action：

| action | payload | 行为 |
| --- | --- | --- |
| SKIP | 当前 `itemId/expectedReviewVersion` | 仅可跳过自动重练，标记跳过并前进，不改评分、薄弱状态和日期；会话版本前进，因此此前事件不再可直接撤销 |
| UNDO | `eventId` | 仅用户最近有效事件、同一会话、同一学习日、无后续会话或复习变更时执行；已完成本轮也能撤销。新增补偿记录关联原事件，恢复前状态、当前位置、队列及本次追加的额度；版本继续递增。首次评分撤销后删除当前状态，回到未学。结果返回 `eventId/session/mutation` |
| RELEARN | 当前 `itemId/expectedReviewVersion` 与 `confirmed=true` | 仅已学卡片；显式确认后 stage=-1、dueDate=今日，保留首次时间、最近评分时间、历史和遗忘次数，不标记当前条目完成、不增加新学数；记录 RELEARN 审计事件，可在无后续操作时撤销 |

所有操作均通过 IndexedDB 队列保留。响应丢失时先查询原操作收据，重试不更换操作 ID；评分冲突不允许像笔记那样自动换版本重提。页面显示当前结果和本地评分供核对，用户重新进入练习才产生额外评分。双设备重复候选识别及候选更正完整界面留 P0-10；本阶段严格拒绝过期版本，绝不重复推进。

## 错误及边界

- `VERSION_CONFLICT`（409）：会话、当前条目、复习版本或撤销窗口不符；`details.current` 含最新会话供核对。
- `SESSION_EXPIRED`（409）：跨天、失效或已无待处理条目；跨天不沿用昨日会话。
- `EARLY_REVIEW_CONFIRMATION`（409）：正常提前评分未经确认；含原 `dueDate`。
- `INVALID_REQUEST/VALIDATION_ERROR`（400）：非法评分/action、未展开答案声明、未确认重新学习、对普通条目执行跳过、数量越界或重复卡片。
- 其他沿用 `NOT_FOUND`（404，含越权 ID）、`EPOCH_EXPIRED`、`OPERATION_ID_REUSED`、401、CSRF 403。

读取不写入学习状态。外部返回的所有 UUID 均校验用户归属；事件、状态、条目、用户 revision、额度和幂等收据同事务回滚。旧代次请求不会执行。算法配置使用 `app.review.algorithm-version` 与 `app.review.success-intervals`，五个正整数且非递减；改变默认参数必须更换版本名，已有事件及日期不批量重算。


P1-04 已增加[个人复习参数与效果观察](review-parameters.md)。新评分记录当前个人版本及完整参数快照；参数变更不批量重算已有日程，评分更正沿用原评分参数，撤销复制原证据。未设置个人参数时继续使用部署默认值。
