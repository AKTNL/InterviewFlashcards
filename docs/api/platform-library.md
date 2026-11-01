# P1-13 平台知识库

V16；个人题库分享仍使用原有绑定接收账号的 `/api/shares`，不提供匿名文件链接。

## 平台归属

`platform_library` 记录名称、说明、状态、管理版本、内容版本、发布快照和创建者。每库独立 `space_kind=PLATFORM` 内容命名空间；数据库约束其密码为 `!`、禁用、普通角色，不能登录、启用或提升角色。此身份仅用于复用已有来源/章节/卡片归属外键，既不属于创建管理员的个人空间，也不占注册/管理员建号额度；用户管理列表及详情排除平台命名空间。

首期每库一份 PDF。解析沿用现有 worker、任务状态、失败重试、完整异常报告和逐章编辑/合并/拆分/排除/确认。未发布时可以修改；发布后必须先下架。所有非放弃章节须确认，至少有一张正式卡片，唯一导入任务必须 READY，才能发布。保留待检查标记的章节仍须先在章节确认中明确选择；发布还须确认对整份 PDF、图片及整理内容具有提供权。

发布冻结内容专用快照，只包含已确认正式内容及必要来源/编辑关系；不含个人注释、收藏、学习设置、会话或历史。PDF 整份提供，包括被放弃章节或周边原文所在页，不做按页裁剪。发布前检查每个文件长度和 SHA-256，结构化快照最多 16 MiB。每次重新发布增加 `content_version`，来源清单包含源内容版本、哈希及快照 ID。

## 管理接口

均需有效管理员会话、`library.manage`；写入需 CSRF、当前账号请求头。不进入个人离线队列。每次调用重新鉴权，资源读取同样鉴权。平台内容始终按库解析命名空间，客户端不能指定所有者 UUID。

- `GET /api/admin/library`：最近 200 条管理记录，含状态、版本、章节/卡片/在途任务/累计领取数量及来源清单。
- `POST /api/admin/library`：`{operationId, name, description}`；名称 1～200 字、说明最多 4000 字。同管理员同编号同内容返回同库；改内容复用编号拒绝。
- `GET /api/admin/library/{id}/preview`：管理记录及最近 30 条审计；返回 `version` 与平台内容 `revision` 供确认。
- `POST /api/admin/library/{id}/sources?operationId=UUID`：multipart `file`，最多 50 MiB；返回原有上传/导入任务结果。相同编号响应丢失可重试，已有来源后禁止上传第二份 PDF。
- `GET /api/admin/library/{id}/jobs`、`GET .../jobs/{job}/chapters`：现有任务及章节字段。
- `POST .../jobs/{job}/retry`：`{operationId, expectedVersion}`，仅失败且没有草稿的任务可重试。
- `GET .../chapters/{chapter}/review`、`GET .../drafts/{draft}`：现有逐章检查/完整草稿字段。
- `POST .../chapters/{chapter}/review`：`{operationId, expectedVersion, action, payload}`，动作及 payload 沿用[逐章检查](chapter-review.md)；命名空间代次由服务端解析。
- `GET .../sources/{source}/content`、`GET .../sources/{source}/pages/{page}/image`：仅管理员来源核对，PDF 支持 Range，响应禁止缓存。

`POST /api/admin/library/{id}/transition`：

```json
{
  "operationId": "UUID",
  "dataEpoch": "管理员当前个人代次 UUID",
  "expectedVersion": 3,
  "expectedRevision": 12,
  "action": "PUBLISH",
  "confirmed": true,
  "rightsConfirmed": true
}
```

`action=PUBLISH|UNLIST|DELETE`；发布需额外 rightsConfirmed。确认前显示名称、章节/卡片数量、在途解析任务、累计领取人数和影响范围。版本或解析/检查进度变化返回 `DATA_PREVIEW_STALE`，必须重新预览；重复原编号原请求返回原收据。状态变更使用管理员幂等收据但不推进其个人学习修订。新增、上传、重试、章节调整、发布、下架、删除及失败均进入专用审计；不记录密码或正文。

## 公开目录及领取

`GET /api/library?page=0&size=20` 可匿名预览，仅返回已发布库；size 1～50。字段为 `id/name/description/content_version/manifest/samples`，包含章节清单、计数、整份来源页数和前 3 题已确认文字预览，不返回存储 key、命名空间所有者或未确认草稿。不开放公共 PDF/图片读取，登录后也需先领取独立资源。

`GET /api/library/claims` 需登录，返回本账号已领取的 `library_id/deck_id/content_version/created_at`。

`POST /api/library/{id}/claim` 需登录及 CSRF：

```json
{
  "operationId": "UUID",
  "dataEpoch": "接收者当前代次 UUID",
  "contentVersion": 1,
  "confirmed": true
}
```

复用 P1-09 内容复制：新 ID、独立文件 key、长度/哈希检查、个人来源/存储配额与全站磁盘限制、失败文件回滚。返回 `{deckId, origin, mutation}`；学习从未学开始，由本人选择范围。原请求重试返回原收据；同账号不同编号重复领取返回原 `deckId` 和 `alreadyClaimed=true`，每账号每库只领取一次，重新发布不重置次数。预览的内容版本不一致返回 `DATA_PREVIEW_STALE`；未发布、下架或删除返回 `LIBRARY_UNAVAILABLE`。领取标记不属于个人 ZIP，删除个人副本或恢复个人空间不返还领取次数，旧 deckId 仅是原领取证据。

## 并发、删除与备份

领取在接收账号写屏障内锁定知识库行；平台管理先锁定其命名空间，再锁知识库行。领取与下架/删除/重新发布按同一行串行化：先提交的操作决定结果；事务、收据、配额或文件失败不留下残缺副本或领取记录。章节写入与解析 worker 共用平台命名空间屏障，预览修订能识别检查和解析变化。

删除为不可逆软删除，立即撤出目录并停止新领取，同时撤销在途解析令牌，晚到 worker 不能保存结果。已领取副本、笔记、进度和来源文件完全独立；下架可再次发布，删除不能复活。

每小时检查已删除至少 7 天的库，无有效任务、无其他命名空间及分享/备份/恢复资源引用时才回收 PDF/图片。数据库保留删除记录、内容结构和来源指纹作为审计证据，`purged_at` 标记资源回收；清理中断重试安全，已删除库不再开放来源读取。整站备份须一起保存完整 PostgreSQL、文件卷和临时卷，覆盖平台命名空间、快照、领取记录和审计；已清理资源无需恢复。个人 ZIP 仅包含本人的独立副本与来源证据，不包含平台状态、管理审计或领取授权，不能重新发布/复活平台库。整站恢复只使用与快照同时保存的数据库和文件卷，参见[发布与恢复手册](../runbooks/release.md)。
