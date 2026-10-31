# P0-04 写入与同步协议

业务 API 继承 [基础响应](baseline.md) 与 [会话/CSRF](auth-and-files.md)。登录、退出属于认证操作，不进入业务收据。当前两个业务写入口（PDF 上传、资料名称编辑）全部使用以下协议；后续草稿、评分等必须复用 `MutationService.execute`，不得绕过用户锁和事务。

## 请求与结果

- `operationId`：客户端在持久化操作时生成的 UUID，网络重试与重载后保持不变。
- `dataEpoch`：最近服务端快照中的 UUID，禁止用新代次替换旧操作的代次重放。
- `expectedVersion`：更新实体时必填的非负整数；新建上传不需要。后续学习提交还必须携带并校验所属会话条目，留 P0-08/09 实现。
- 幂等键：`(userId, dataEpoch, operationId)`。请求摘要包含操作类型、资源 ID 和逻辑负载；递归排序 JSON 对象键，保留数组顺序与值类型。上传摘要包含规范化显示文件名、文件 SHA-256、字节数，文件内容不能只用文件名代替。
- 同键同摘要直接返回原 `data`（包含原时间、版本和 revision）；同键不同摘要返回 `409 OPERATION_ID_REUSED`。恢复代次检查优先于历史收据，旧代次即使有收据仍被拒绝。

成功数据沿用原业务字段，并新增 `mutation`：

```json
{
  "data": {
    "source": { "id": "UUID", "originalFilename": "面试.pdf", "sha256": "64位摘要", "pageCount": 222, "byteSize": 1234, "createdAt": "UTC时间", "version": 1 },
    "mutation": { "operationId": "UUID", "dataEpoch": "UUID", "entityVersion": 1, "revision": 2, "serverTime": "UTC时间" }
  },
  "meta": { "requestId": "本次请求编号", "serverTime": "本次响应的UTC时间" }
}
```

`meta` 属于本次 HTTP 响应，不随收据重放；`data.mutation` 属于原业务提交。实际改变数据时用户 revision 增加一次；相同文件的新操作上传只增加收据，`duplicate=true`，不改变文件名或 revision。每次成功名称编辑推进实体版本与 revision，即使名称相同。

## 当前接口

| 方法/路径 | 负载 | 返回 |
| --- | --- | --- |
| `POST /api/sources` | multipart：`file`、`operationId`、`dataEpoch` | `source`、`duplicate`、`mutation` |
| `PATCH /api/sources/{id}` | JSON：`operationId`、`dataEpoch`、`expectedVersion`、`name` | `source`、`mutation` |
| `GET /api/sync/revision` | 无 | `dataEpoch`、`revision` |
| `GET /api/sync/snapshot` | 无 | `dataEpoch`、`revision`、`sources`（所有来源元数据，不含文件正文） |
| `GET /api/sync/receipts/{operationId}?dataEpoch=UUID` | 无 | 原成功提交的 `data`；没有收据为 `404 NOT_FOUND` |

名称为 1～255 字符、非纯空白、不含路径分隔符或控制字符；仅改变显示名称，不移动原文件，不触发解析。上传仍受 50 MiB PDF 限制。缺少协议字段或 UUID 格式不正确为 `400 INVALID_REQUEST`；JSON 字段校验错误为 `400 VALIDATION_ERROR`。所有接口需登录；写请求同时需要有效 CSRF。跨用户资源和收据返回 404。

快照在事务内对用户行持共享锁，得到与 revision 一致的资料列表。正文通过原鉴权接口按需获取。后续任务在该快照中增加相应摘要；目前不存在草稿/学习内容的占位成功结果。P0-04 不实现复杂增量、删除墓碑或全业务冲突列表。

## 错误与客户端行为

| 错误 | 状态 | 行为 |
| --- | --- | --- |
| `VERSION_CONFLICT` | 409 | `error.details.current` 返回当前完整来源元数据（含版本）；本地内容与远端值均保留，停止此操作自动重试 |
| `EPOCH_EXPIRED` | 409 | 返回当前代次和 revision；清除旧服务端快照，重新读取；旧待提交内容转为待处理，不自动改代次 |
| `OPERATION_ID_REUSED` | 409 | 保留本地内容供核对，不重放 |
| `UNAUTHENTICATED` | 401 | 保留队列，登录同一用户后继续检查；不向其他账号提交 |
| `VALIDATION_ERROR`、`INVALID_PDF` 等 | 400 | 标为失败并保留内容，修正后以新操作提交 |
| `CSRF_INVALID` | 403 | 保留内容并等待用户刷新安全凭证，显式重试 |
| `SERVICE_UNAVAILABLE`、`STORAGE_UNAVAILABLE`、`INTERNAL_ERROR`、网络/超时/无效成功响应 | 5xx / 本地错误 | 保留相同操作 ID，退避重试；先查收据，避免已成功写入又报版本冲突 |

IndexedDB 提交完成之后才开始发送。状态为待同步、同步中、已同步、失败、待处理；只有匹配操作 ID、代次及实体版本的服务端收据才可置为已同步。PDF Blob 与编辑内容均持久化；已确认上传释放 Blob，失败或旧代次上传可下载本地副本。存储失败必须提示，不能继续发送或声称本地已保存。

激活页面、重新联网、重新登录、显式连接和退避计时均触发刷新。自动重试先核对代次与收据；未确认的更新再读取实体版本。重载遗留的同步中状态按未知结果处理。用户可以显式用本地名称基于最新服务端版本、新操作 ID 再次提交，也可以放弃待处理操作。旧代次操作只允许核对、复制或放弃。

## 事务与扩展约束

所有业务写入先以 `SELECT ... FOR UPDATE` 锁定用户，检查代次与收据，再执行归属和版本检查；业务数据、用户 revision 与收据在同一数据库事务提交。个人规模选择用户级串行写入，以简化并发和后续恢复屏障；不同用户互不串行。源文件发布保留回滚清理钩子。

P0-11 完整恢复现已使用同一用户锁，事务内替换并递增代次和 revision；后台解析等业务写入也遵循该锁约定。恢复结果以独立任务 ID 去重，不复制旧操作收据；旧代次队列保留供核对，不自动重放。评分绑定、评分冲突候选和统计更正见 P0-08～10 对应契约，完整恢复见[备份恢复 API](backups-and-restores.md)。
