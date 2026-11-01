# P1-12 管理员与用户管理 API

日期：2026-11-01。迁移 V15。前后端同时升级；不自动创建管理员或提升旧账号。后台路径 `/admin`，所有管理请求同源会话、CSRF、`Cache-Control: no-store`，仍遵循 `X-Account-ID` 跨账号保护。

## 角色与身份

`app_user.role` 为 `USER`（迁移及新用户默认）或 `ADMIN`。稳定能力集中定义在 `Capabilities`：`users.manage`、`invitations.manage`、`library.manage`、`dashboard.read`，未配置能力默认拒绝；首期 ADMIN 具有上述管理能力，普通用户不开放管理能力。个人邀请仍独立检查 P1-11 的权限、累计成功数及全站限制；ADMIN 不自动获得个人发码权。平台知识库业务见 [P1-13 API](platform-library.md)，Dashboard 留 P1-14；账号列表与详情排除 PLATFORM 内容命名空间。

登录响应和 `GET /api/auth/me` 增加 `role`、`capabilities`、`mustChangePassword`。角色和账号状态由服务端逐请求重新读取，不能信任 Cookie 中的旧角色或客户端字段。停用/改密/维护角色变更递增 `credential_version`，旧会话下次请求 401，启用不复活旧会话。成功登录更新 `last_login_at`，升级前没有历史登录数据的账号显示“尚无记录”。

初始/重置密码登录后可访问 me、csrf、password、logout，其余业务请求返回 `403 PASSWORD_CHANGE_REQUIRED`。前端引导个人中心先改密，暂停学习同步；新密码不能与当前密码相同（`PASSWORD_REUSED`）。改密后所有旧会话失效，需重新登录。停用账号不能登录。管理员的普通学习接口仍按本人 ID 检查归属，无跨用户私人内容或备份读取能力。

## 查询

- `GET /api/admin/users?search=&state=ALL&invitation=ALL&page=0&size=20`：用户名/昵称字面包含搜索（忽略大小写）；状态与邀请资格分别接受 `ALL / ENABLED / DISABLED`。页从 0 开始，size 1～50。返回 `{items,total,page,size}`。
- `GET /api/admin/users/{id}`：同一份用户资料，外加最近 50 枚个人邀请的脱敏状态、最近 30 条审计。不返回邀请码明文/摘要、密码散列、会话或学习正文。
- `GET /api/admin/configuration`：注册开关、累计新增账号上限及已占用数、个人/服务器容量配置和修改渠道（环境配置，只读）。

列表字段：`id, username, nickname, role, enabled, created_at, last_login_at, account_origin, must_change_password, admin_version, invitation_enabled, total_limit, successful_count, remaining_quota, storage`。storage 复用现有实际文件用量。时戳为 UTC；后台展示 Asia/Shanghai。审计字段为 actor、来源、目标、action、非敏感 before/after、时间及结果。

## 写操作

后台写入只联网直接提交，不进入 IndexedDB 或普通学习同步队列，不增加个人业务 revision。所有写入在注册全站锁内重新校验操作者当前角色、启用状态与改密要求，目标只能是 USER；管理员账户通过维护工具管理。

| 请求 | JSON | 行为 |
| --- | --- | --- |
| `POST /api/admin/users` | `{operationId,username,password,nickname?}` | 新增普通用户，返回 UUID；强制首次改密，创建默认学习偏好，来源 `ADMIN_CREATED` |
| `POST /api/admin/users/{id}/status` | `{version,enabled}` | 停用/启用，均撤销旧会话；停用撤销未消费个人邀请，保留资料和成功计数 |
| `POST /api/admin/users/{id}/permission` | `{version,enabled,totalLimit}` | 复用 invitation_permission，总额 0～10000；关闭时撤销未消费邀请，不清零成功数 |
| `POST /api/admin/users/{id}/invites/{invite}/revoke` | `{version}` | 只能撤销属于目标用户的未消费邀请 |
| `POST /api/admin/users/{id}/password-reset` | `{version,password}` | 设置临时密码，撤销所有旧会话，必须改密后继续使用 |

`version` 必须等于详情中的 `admin_version`；成功后递增，不影响个人内容版本。过期返回 `409 VERSION_CONFLICT`，必须重新读取后确认。未知字段（例如 role、capabilities、userId）拒绝，非法 UUID、负版本、缺少参数返回 400。篡改目标管理员返回 403，不存在或邀请归属不符返回 404。

建号采用全局唯一 operationId 和持久收据，提交成功后响应丢失，显式重试同一请求返回原 ID；变更原始参数或换操作者复用编号返回 `OPERATION_ID_REUSED`。请求摘要先 SHA-256 再加盐 BCrypt，不保存密码明文或直接密码摘要。收据长期保留，不因重置密码或个人恢复失效。其余操作由版本防止重复执行，响应不确定时先刷新核对，绝不自动重放。

直接建号沿用用户名、保留名、密码和昵称校验，不消耗邀请人的名额；独立的 `admin_account_allocation` 记录永久全站名额。全站容量 = 旧有 `registration_use` 数 + 管理建号分配数；邀请注册和后台建号共用 registration_guard，最后名额只能成功一次。后台建号也要求全站注册开放。受控首个管理员创建可在注册关闭时引导初始化，但仍受全站上限约束。失败回滚账号、名称预留、学习偏好、分配和收据。

成功操作与审计同事务；经过服务层的失败/拒绝另事务记录结果（不记录请求正文或凭据）。个人学习 ZIP 白名单不含账号、角色、启停、邀请、名额、管理收据或审计；恢复只更新本人学习资料、epoch/revision，不改变权限、状态与凭据。

## 维护

首个管理员使用 `AccountAdmin create-admin NEW_USERNAME` 交互输入独立密码；用户名已存在时拒绝，绝不覆盖凭据。显式绑定旧账号使用 `role EXACT_USERNAME ADMIN`，回收使用 `role EXACT_USERNAME USER`，旧账号 ID、预置绑定和内容保留。角色变更与管理写入共用锁；禁止并发降级最后一个启用管理员。Web 不允许停用、重置或修改管理员角色。详见 [账号维护手册](../runbooks/accounts.md)。
