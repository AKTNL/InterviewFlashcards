# P1-05：变更通知与按修订增量刷新

继承[同步与评分冲突协议](cross-device-sync.md)。所有接口按实际登录用户归属读取，沿用 Cookie、`X-Account-ID`、账号状态与凭据版本检查；返回内容禁止缓存。GET 不产生学习修订或写入收据。

## 通知

`GET /api/sync/notifications?dataEpoch=UUID&since=42&learningDate=2026-11-01`

使用异步长轮询。每秒检查已提交的用户代次、修订与服务端学习日，有变化即返回；无变化约 20 秒返回同样的游标，客户端继续等待。响应沿用标准 `{data,meta}`，`data` 仅含 `dataEpoch/revision/learningDate`，没有正文、文件或操作负载。通知只是补拉提示，不能直接推进客户端游标。

不长期占用 Servlet 线程或数据库连接。单进程最多 512 个等待、每账号最多 8 个；超限返回 `TASK_LIMITED`。等待期间也检查账号启用状态、凭据版本、强制改密及会话有效性，撤权/会话失效返回相应错误。服务器重启会断开等待，客户端使用原游标重连，不依赖内存通知历史。

前台可见且在线时等待；隐藏、断网、退出或切换账号取消客户端连接。通知失败按 5/10/20/40/80/120 秒退避，保留原来的活跃页 30 秒修订检查及重新联网/激活检查。通知错误显示降级提示，不把已确认的学习记录改成失败。身份失效仍按已有登录流程处理。

## 补拉

`GET /api/sync/changes?dataEpoch=UUID&since=42`

`since` 为最后完整应用的非负修订；忽略客户端提供的用户 ID。成功示例：

```json
{
  "dataEpoch": "当前UUID",
  "fromRevision": 42,
  "revision": 45,
  "learningDate": "2026-11-01",
  "fullRequired": false,
  "changes": [
    {"table":"personal_annotation","key":{"id":"标注UUID"},"row":{"id":"标注UUID","card_id":"卡片UUID","notes":"修正后的笔记","version":2}},
    {"table":"card","key":{"id":"已删除卡片UUID"},"row":null},
    {"table":"review_policy","key":{"version":2},"row":{"version":2,"algorithm_version":"版本名","intervals":[1,3,7,14,30]}}
  ]
}
```

示例省略了未列出的业务字段；实际 `row` 与全量快照对应行一致。`source_document` 使用 `sources` 的 camelCase API 字段，其他表沿用全量 `data` 的 snake_case。排除 `user_id/storage_key/raw_evidence/attempt_token`；二进制资源继续由鉴权接口读取。

同一行多次变更合并成当前最终状态；`row:null` 为删除标记。删除后重建同键行返回当前行。主键默认 `id`，以下例外均不含用户 ID：

| 表 | key |
| --- | --- |
| card_tag | card_id、tag_id |
| learning_preference_chapter | preference_id、chapter_id |
| review_replay_allowance | card_id、learning_date |
| review_policy | version（JSON 整数） |

在一个 REPEATABLE READ 事务中持有用户共享锁，核对 `(since,revision]` 的修订覆盖并读取当前行，避免并发写入或清理造成半份响应。相同游标可重复请求；无变化返回空数组。客户端构造新状态后一次性替换状态和游标，失败时保留旧状态，不逐条推进游标；编辑表单与待同步操作沿用原有版本和本地副本。

## 全量兜底

下列情况只返回头信息、`fromRevision/fullRequired:true/reason`，客户端调用原 `GET /api/sync/snapshot` 并整体替换：

| reason | 条件 |
| --- | --- |
| EPOCH_CHANGED | 恢复备份等操作改变学习代次 |
| CURSOR_AHEAD | since 大于当前服务端修订 |
| CURSOR_EXPIRED | 变更历史过期、升级前游标或覆盖存在空档 |
| TOO_MANY_REVISIONS | 跨越超过 1000 个修订 |
| TOO_MANY_ROWS | 涉及超过 5000 个不同表/行键 |
| SCHEMA_CHANGED | 发现客户端协议未涵盖的数据表 |

首次连接、用户点击“刷新与重试”也读取全量。客户端遇到无效增量基线、未知表或行键不一致时放弃该增量，读取全量；网络失败不清空旧状态。跨代次旧队列保留为待处理，禁止自动重放。日期变化即使修订未变，也刷新学习视图。

## 持久化与保留

V19 新增 `sync_revision` 和 `sync_row_change`。前者记录修订覆盖，后者仅存账号、代次、修订、表名与行主键；不复制历史笔记/答案。24 张同步业务表通过延迟到事务提交的触发器记录最终修订，包含后台解析、复合关联、删除与恢复；业务/收据失败时一并回滚。新业务写入仍必须遵守原用户锁和推进修订的协议。

每小时在同一事务中清理超过 7 天的记录。清理后旧游标以全量恢复；个人 ZIP 不包含这两张传输辅助表，整站数据库备份自动包含。升级保留旧账号的现有修订，不伪造升级前的增量历史。

前端维护完整内存快照，对变更表线性合并；已接入筛选的消费者只因相关表更新而重新查询，代次/学习日变化或全量替换始终刷新。初次登录仍需要完整快照；本任务没有引入分页历史下载或任意资料的大数据离线缓存。
