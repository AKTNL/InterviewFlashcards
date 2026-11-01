# P1-09 题库内容副本 API

所有接口同源、必须登录；写请求需 CSRF 和当前账号身份校验。信封及错误结构沿用 [基础 API](baseline.md)。不提供匿名下载或上传分享 ZIP 的接口。

## 所有者预览与创建

`GET /api/shares/decks/{deckId}/preview` 只读取当前用户题库；没有已确认章节中的正式卡片时返回 `SHARE_EMPTY`。返回 `dataEpoch / revision`、题库名、已确认章节名、`counts`、每份来源文件的名称/指纹/总物理页数和 `includedPages`。来源 PDF **整份包含第 1～总页数页**，即使其中有未确认章节。前端先展示这一范围，再要求确认有权提供 PDF、图片和整理内容。

`POST /api/shares`：

```json
{
  "operationId": "UUID",
  "dataEpoch": "UUID",
  "deckId": "UUID",
  "expectedRevision": 12,
  "recipient": "friend_username",
  "expiresInHours": 24,
  "rightsConfirmed": true
}
```

`recipient` 精确匹配已注册登录用户名，不允许分享给自己。有效期 1～168 小时；每份邀请固定最多领取 1 次。每个账号最多保留 20 个尚未领取且未撤销的邀请（含过期但尚未撤销的邀请）。预览修订变化返回 `DATA_PREVIEW_STALE`；接收账号不可用返回 `SHARE_RECIPIENT_INVALID`。创建成功返回 `{ share, mutation }`，同操作 ID/相同请求返回原收据；复用操作 ID 改变请求返回 `OPERATION_ID_REUSED`。

服务器在所有者写屏障内生成内容专用快照和独立快照文件；只包含已确认正式卡片及必要的章节、来源、支持编辑的草稿关系。后台提取证据、解析报告、未确认草稿、个人笔记/提示/收藏、学习历史/设置/会话和账号凭据不进入快照。文件长度和 SHA-256 必须吻合。

## 邀请与领取

- `GET /api/shares` 返回 `{ outgoing, incoming }`，各按创建时间倒序最多 100 条。
- `GET /api/shares/{id}` 仅允许所有者及绑定接收者查询；其他账号返回 `NOT_FOUND`。
- 邀请字段：`id / outgoing / recipient / expiresAt / expired / revoked / claimCount / maxClaims / manifest`。`manifest` 包含分享者显示名、数量、章节名、完整来源页数、生成时间、源修订、每张卡片的源 ID/内容版本、快照 ID 和内容哈希；不包含存储 key、密码或内部用户 ID。
- `POST /api/shares/{id}/claim`：`{ operationId, dataEpoch, confirmed: true }`。仅绑定接收者可在有效期内主动确认。过期、撤销或已领完返回 `SHARE_UNAVAILABLE`。代次错误返回 `EPOCH_EXPIRED`。
- 成功返回 `{ deckId, origin, mutation }`。为题库、卡片及关联生成新 ID，复制 PDF/图片到接收方自己的存储 key，`review_version` 从 0 开始，不生成复习状态或事件；学习范围由本人在设置中选择。

领取与撤销锁定同一邀请行，只有一个先完成的确定结果。领取使用 `MutationService` 的账号写屏障、请求摘要和收据；响应丢失后使用原操作 ID 重试返回原结果。不同操作 ID 不能再次领取同一邀请。接口没有离线自动领取；页面网络失败保留原操作 ID。重新进入页面可通过已领取状态及题库来源核对结果。

容量计入邀请快照和接收方独立文件；来源数量与存储配额、全站磁盘上限均生效。任何文件校验、容量、SQL 或收据失败都会回滚可见题库和领取次数，删除本次新增文件；原快照保留供重试。

## 撤销、备份与恢复

`POST /api/shares/{id}/revoke` 仅所有者可用，重复撤销安全。撤销后释放快照文件并阻止后续领取；已领取副本不受影响。领取成功也释放分享者的快照文件。过期邀请可由所有者撤销以释放空间。

题库 `copy_origin` 保留来源清单和内容版本，随个人备份导出和恢复，其中源 ID 不做重映射。邀请及授权记录不属于个人备份；恢复个人空间会在替换事务中撤销其发出的邀请并释放快照，不恢复旧授权。已领取副本不受原题库更新、删除或恢复影响。格式 1 的 V12 之前备份仍可在新服务恢复，新增来源元数据按空对象/普通上传补齐。
