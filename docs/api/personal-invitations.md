# P1-11 个人邀请码与分步注册 API

沿用 [P1-08](registration-and-accounts.md) 的用户名、密码、响应信封和同源 Cookie/CSRF。以下响应由 Spring Security 添加 `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`；所有 POST，包括匿名预校验，均须 CSRF。所有权只从会话确定，不接受邀请人 ID。

| 方法与路径 | 请求与结果 |
| --- | --- |
| `GET /api/account/invites` | 登录；`{allowed,totalLimit,successfulCount,remainingQuota,canGenerate,reason,latest}` |
| `POST /api/account/invites` | 登录；无请求体；`{invite,token}`，token 仅此响应返回 |
| `POST /api/account/invites/{id}/revoke` | 登录；只允许撤销本人邀请；成功 `true`，他人或不存在的 ID 为 404 |
| `POST /api/auth/invite/validate` | 匿名；`{invite}`；可用返回 `true`，不登录、不消费或预留名额 |
| `POST /api/auth/register` | 保持原 `{invite,username,password,nickname?}`；事务内重新校验所有限制，成功不自动登录 |

`latest` 是本人最近一枚邀请或 null，字段为 `{id,label,expiresAt,remainingUses,usedCount,status}`，不含摘要、明文或受邀账号。`label` 使用邀请记录 UUID 前八位作脱敏标识，不能兑换注册。状态为 `AVAILABLE / USED / EXPIRED / REVOKED`（可使用、已使用、已过期、已撤销）；被撤销优先显示撤销，已使用优先于到期。`remainingUses` 为该码尚未消费的次数，是否实际可用仍取决于状态、邀请人资格与全站开关。`remainingQuota` 是总额度减历史成功数，不因临时关闭权限清零。

个人邀请固定 7 天、1 人，同时最多一枚未到期、未撤销、未用完的码。`allowed` 表示维护者授权，`canGenerate` 综合注册开关、权限、个人剩余额度、全站上限和已有有效码；`reason` 给出不能生成的原因。未授权的新账号默认没有 `invitation_permission` 记录。

V14 增加 `invitation_permission`、`registration_guard`，并给 `registration_invite` 增加 `inviter_id / invite_source`。旧维护邀请来源默认为 `MAINTENANCE`，邀请人为空，原到期时间及次数不变；个人邀请为 `PERSONAL`，关联权限记录，成功后增加永久成功计数。注册记录、权限和邀请仍不在个人备份白名单内。

`REGISTRATION_MAX_ACCOUNTS` 默认 100，必须为正整数，表示全站通过邀请码成功注册的累计上限，含 V11 以来维护邀请与个人邀请的 `registration_use`；预置/迁移旧账号不占新注册名额。部署启用前维护者应确认具体上限。`REGISTRATION_ENABLED` 继续默认 false；全站注册上限或邀请人的权限变化不影响已有账号登录和学习。

注册、预校验、生成、撤销及维护权限修改先锁全站 `registration_guard`；注册随后锁邀请行，维护命令撤销也锁同一邀请行。小规模单站以短事务串行化准入，避免最后一个全站名额、个人名额和同时有效码被并发超发；用户名冲突、初始化或记账失败全部回滚。预校验事务结束即释放锁，结果不能当作后续注册授权。

匿名预校验与最终注册共用可信来源每 15 分钟 20 次额度。发码另按来源和当前账号各 20 次/15 分钟限流，成功或失败均计数；不自动重放。

| HTTP / code | 含义 |
| --- | --- |
| 403 `REGISTRATION_CLOSED` | 注册关闭 |
| 403 `REGISTRATION_LIMITED` | 全站累计注册名额耗尽 |
| 403 `INVITATION_NOT_ALLOWED` | 发码账号未授权、禁用或成功额度耗尽 |
| 409 `INVITE_ALREADY_ACTIVE` | 已有有效码，先查询；需新码时明确撤销后另行生成 |
| 400 `INVITE_INVALID` | 统一覆盖无效、到期、撤销、使用完、邀请人资格或额度失效；不暴露邀请人资料 |
| 429 `RATE_LIMITED` | 等待限流窗口，不自动重试 |

客户端仅在组件内存保留邀请码；不放 URL、历史 state、LocalStorage、SessionStorage、IndexedDB 或离线队列。注册成功仅将非敏感用户名经路由 history state 预填登录页。密码提交后清空，邀请最终失效时回到验证步骤并保留用户名/昵称。生成响应不确定时先 GET 查询状态，不恢复明文或自动重发；页面刷新、离开或手动刷新状态清除明文，再次分享需明确撤销后单独生成。
