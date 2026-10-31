# 完整备份与安全恢复 API（P0-11）

所有接口要求登录；POST 要求同源 CSRF。JSON 使用既有 `data/meta` 信封。只操作当前登录用户的学习空间，不接受目标用户参数。任务 UUID 不能越权读取、下载或确认。

## 导出

- `POST /api/backups`：`{"operationId":"UUID"}`，返回持久化任务。同一用户和 operationId 重试返回同一任务，不重复生成。备份任务不更改学习 revision。
- `GET /api/backups`：最近 30 项，按创建时间倒序。
- `GET /api/backups/{id}`：查询进度和结果。
- `GET /api/backups/{id}/download`：仅 READY 可下载，鉴权 ZIP 附件，支持资源流和 Range，`Cache-Control: no-store`。

任务字段：`id/status/progress/created_at/updated_at/error_code/format_version/operation_id`，完成后增加 `manifest`。不暴露 storage_key、user_id 或内部文件路径。状态：`QUEUED → RUNNING → READY | FAILED`。导出持同用户共享锁固定所有结构化行和不可变资源清单，释放锁后计算哈希与压缩；学习写入无需等待压缩完成。

## 上传、预检和替换

- `POST /api/restores`：multipart `file`、`operationId`。仅上传临时备份，不立即替换。相同 ID、相同文件哈希返回原任务；相同 ID 不同文件返回 `OPERATION_ID_REUSED`。请求或响应中断时保留文件和操作 ID 重试。
- `GET /api/restores`、`GET /api/restores/{id}`：同导出查询。状态：`QUEUED → VALIDATING → AWAITING_CONFIRMATION → RESTORING → READY`；非法包或执行失败为 FAILED。
- `POST /api/restores/{id}/preview`：仅预检通过且等待确认时可用；刷新当前空间数量和确认基线，并清除旧错误。页面刷新预览后须再次勾选确认。
- `POST /api/restores/{id}/confirm`：`{"dataEpoch":"预览的 expectedEpoch","expectedRevision":123,"confirmed":true}`。必须与当前 owner 及预览基线均匹配，否则 HTTP 409 `RESTORE_STALE`。确认后后台准备文件，再在数据库替换事务中再次核对基线。任务 ID 即恢复执行的去重标识，RESTORING/READY 重复确认只返回原结果。

`validation_result` 包含 `manifest`（备份时间与数量）、`scope`、`currentCounts`、`expectedEpoch/expectedRevision`；READY 后增加 `restoredEpoch/restoredRevision/completedAt`。`progress` 为工作阶段进度（排队 0、校验/导出 10、等待确认 50、恢复 60、完成 100），不表示可靠剩余时间。

预检在独立、最终回滚的临时 owner 上实际写入 PostgreSQL，验证全部现有唯一键、外键、检查约束；不会删除、修改或锁定当前用户学习数据，也不会持久化临时账号。额外核对来源页码、资源归属、卡片与草稿题库关系、内容页码、事件/条目/补偿关系、事件状态格式及当前复习状态与最新事件的一致性。日期原样恢复，不按当天重算。数据库 identity 分配允许产生正常间隙。

正式恢复先将资源发布到全新的不可变 UUID 命名空间，再以同用户 `FOR UPDATE` 锁等待已开始写入完成，事务内按依赖关系替换数据，最后递增 dataEpoch 与 revision 并同时提交 READY。数据库或文件准备失败时原空间不变。确认到提交之间有业务写入则重新预览，不覆盖新修改。

所有实体 ID 和关系引用重新映射，避免跨账号恢复时全局主键冲突；文本笔记、题目和正文保持原值。文件内 `user_id` 即使存在也被忽略，始终强制绑定登录用户；账号、密码、时区账号配置和 Cookie 均不替换。学习事件相对次序保留，重新分配全局事件序号；收据不恢复，事件和评分候选使用新代次。保存的同日会话可通过新 ID 继续，跨天会话仍遵循现有只读规则。

## 格式与限制

只支持显式的 `formatVersion: 1`，没有静默兼容或降级。

```text
manifest.json   # formatVersion、createdAt、dataEpoch/revision、counts、algorithmVersions、files
data.json       # 以下全部 23 张业务表的完整结构化行（剔除归属及存储/线程令牌）
sources/{原来源实体UUID}.pdf
assets/{原资源实体UUID}.png 或 .jpg
```

`files` 对每个文件（不包含 manifest 自身）记录 `path/byteSize/sha256`，必须与 ZIP 条目集合完全一致；数据内资源大小/哈希也须一致。备份不是加密包，须自行妥善保存。

纳入 `source_document/deck/module/chapter/import_job/draft_card/draft_edit_operation/chapter_check_progress/card/asset/source_span/follow_up/tag/card_tag/personal_annotation/learning_preference/learning_preference_chapter/review_state/learning_session/session_item/review_event/review_replay_allowance/sync_conflict`。来源 PDF、独立图片、正文与追问、技术纠错、提示笔记收藏、检查标记、调整证据、偏好及范围、评分/撤销/更正和候选均保留。QUEUED/PARSING 解析任务恢复为 FAILED/RESTORED_INTERRUPTED，保留提取证据与已有草稿，须显式重试，不恢复执行线程。

限制：ZIP 50 MiB、解压总量 512 MiB、单 JSON 32 MiB、条目总数 10,000、业务行总数 200,000。限制同时适用于导出和导入；与既有 Nginx/multipart 50/51 MiB 配套。拒绝损坏/截断 ZIP、重复条目、目录、绝对路径、反斜杠、路径穿越、额外/缺失文件、不合法字段/表/关联、异常压缩包和未知版本。所有解压读取均使用流式累计限制，不信任 ZIP 声明大小。

任务失败码：`BACKUP_INVALID`、`BACKUP_VERSION_UNSUPPORTED`、`BACKUP_FAILED`、`RESTORE_FAILED`、`RESTORE_STALE`、`BACKUP_INTERRUPTED`、`RESTORE_INTERRUPTED`、`BACKUP_EXPIRED`。失败详情留任务编号与日志类型，不向浏览器暴露 SQL、路径或包内内容。

单 JVM 持久化任务轮询；重启中断的导出标为失败，预检重新排队，未提交恢复回到等待确认并作废旧基线。已提交恢复的 READY 与代次在同一事务中，因此重启不会重复替换。下载和上传备份文件保留 7 天，小时清理；已完成恢复结果保持 READY。重启清理遗留的备份解压临时目录。旧业务文件及失败准备产生的未引用持久化文件保留，避免破坏在途阅读或既有备份；自动资源垃圾回收、自动备份与保留策略仍是后续运维范围。

## 多设备

当前设备与其他设备检测代次变化后重新挂载业务页面、丢弃旧服务端缓存，刷新权威快照。恢复页面可留在结果页，其他业务页面返回今日学习。旧会话/实体 ID 不能用于新代次自动写入。IndexedDB 中旧代次待同步内容变为 `needs-attention/EPOCH_EXPIRED`，保留副本供核对，不换代次自动重放。所有旧代次业务请求仍由服务端统一拒绝。
