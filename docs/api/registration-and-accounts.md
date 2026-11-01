# P1-08 注册、密码与容量 API

P1-11 扩展个人发码、匿名预校验、邀请人成功额度和全站累计注册上限，见 [个人邀请 API](personal-invitations.md)。原注册请求和注册后不自动登录的行为保持兼容；旧维护码允许邀请人为空。登录页当前入口为“使用邀请码加入”，密码修改入口位于“个人中心”。

沿用 [响应信封](baseline.md)、会话 Cookie 与 CSRF。所有 POST 包括匿名注册都必须先获取 `/api/auth/csrf` 并发送返回的头。身份、邀请码和维护数据不进入个人 ZIP。

| 方法与路径 | 请求 / 结果 |
| --- | --- |
| `GET /api/auth/registration` | 匿名可读，`data: boolean` 表示是否开放注册 |
| `POST /api/auth/register` | JSON `{invite, username, password, nickname?}`；成功 `data: true`，不创建登录态 |
| `POST /api/auth/password` | 登录后 JSON `{currentPassword, newPassword}`；校验原密码并修改，`data: true`，当前及其他设备都须重新登录 |
| `GET /api/auth/me` | 原字段增加可空 `nickname`；不返回账号来源、凭据版本或邀请信息 |
| `GET /api/storage/usage` | 登录后返回 `{usedBytes, maxBytes, sources, maxSources, maxImports, maxBackups}`，仅当前用户 |

用户名规范化：去除首尾空白，英文字母转小写。新用户名匹配 `[a-z][a-z0-9_]{2,31}`，保留 `admin / administrator / root / system / support / api / null / undefined`。数据库以 `account_name.normalized` 主键兜底。新账号登录支持相同规范化；旧名字优先精确匹配，未静默改名。昵称可选，最长 60 个 UTF-16 单元，不含控制字符，空白昵称保存为 null。

密码至少 12 个 Unicode 字符、最多 72 个 UTF-8 字节，不含控制字符，不 trim。所有新 hash 使用 BCrypt cost 12。注册密码、改密、邀请码不写 LocalStorage/IndexedDB，不加入同步队列，不自动重放。注册网络超时或响应丢失显示“先尝试登录”，已存在用户名的提示也包含该引导。

邀请码由维护命令产生 32 随机字节，以 43 位 Base64URL 无填充形式展示一次。库内摘要、到期时间、最大/已用额度、撤销标记及使用者记录独立于学习备份。同一邀请的并发提交加行锁；不同邀请争抢同一用户名由数据库唯一约束决定，失败事务退回全部额度。

## 身份切换和会话

浏览器业务请求附 `X-Account-ID: <上次确认的用户 ID>`。它与 Cookie 用户不符时返回 `409 ACCOUNT_CHANGED`，不执行业务；响应也附实际会话用户 ID。客户端不能用它改变服务端归属。原有不带此头的客户端仍由会话及业务归属校验授权。PDF、页面图片、下载使用已有归属与 no-store，内置阅读器随账号页面卸载取消加载。

切换账号前停止原同步；其他标签页通过 LocalStorage 标记、storage 事件和 BroadcastChannel 得知变化后清空页面、取消在途请求并要求重新确认身份。每次请求再次检查共享标记，以应对浏览器延迟派发事件。队列按 userId 保留，切回原账号后沿用原操作 ID / dataEpoch 取得收据或继续核对。旧代次仍不能重放。

改密和维护重置都会递增账号凭据版本；旧会话下一次 API 返回 `401 UNAUTHENTICATED`，不能继续读写。每次 API 校验数据库版本，故重置无需重启后端；重启不再覆盖 hash。

## 限流与配额

| 配置环境变量 | 默认 | 含义 |
| --- | --- | --- |
| `REGISTRATION_ENABLED` | false | 是否允许邀请码注册 |
| `USER_STORAGE_MAX_BYTES` | 536870912 | 每用户当前 PDF/图片和未清理的备份/恢复副本实际字节 |
| `USER_SOURCE_LIMIT` | 20 | 每用户来源数，重复上传不占新额度 |
| `USER_IMPORT_LIMIT` | 2 | 每用户 QUEUED / PARSING 导入数 |
| `USER_BACKUP_LIMIT` | 2 | 每用户待处理导出/恢复数，含等待确认的恢复 |
| `GLOBAL_TASK_LIMIT` | 20 | 全站上述待处理任务总数 |
| `GLOBAL_STORAGE_MAX_BYTES` | 5368709120 | files 与 temporary-files 实际总占用上限 |
| `STORAGE_RESERVE_BYTES` | 536870912 | 两目录所在文件系统需要保留的可用空间 |

容量不足时在服务端拒绝；学习数据/收据事务回滚。导出 ZIP 发布时计入个人额度；恢复采用“当前资源 + 新资源”的保守检查，且检查备份内来源数，成功替换后凭据和他人空间不变。已删除业务引用的旧不可变文件、失败准备留下的未引用文件仍计入全站实际占用，人工处理须遵守备份/恢复手册。暂存、导出和解包会额外检查工作空间：单次上传预留 50 MiB，解包预留 512 MiB，导出预留 562 MiB；因此接近全站上限前可能提前拒绝，避免处理中写满磁盘。数据库正文及索引不计入个人二进制额度，部署者另行监控 PostgreSQL 卷。

认证尝试以 PostgreSQL 时钟计 15 分钟窗口：登录每来源 60 次且每规范化账号 20 次，注册/改密各每来源 20 次，成功或失败均计数。只采 servlet remoteAddr，不读取用户提供的 X-Forwarded-For。生产代理拓扑及维护方法见 [账号手册](../runbooks/accounts.md)。

| HTTP | code | 处理 |
| --- | --- | --- |
| 403 | REGISTRATION_CLOSED | 联系维护者，注册目前关闭 |
| 400 | INVITE_INVALID | 无效、格式错误、过期、撤销、耗尽使用同一提示 |
| 400 | ACCOUNT_NAME_INVALID / PASSWORD_INVALID | 按页面规则修改输入 |
| 409 | USERNAME_TAKEN | 换名字；若刚提交过注册先尝试登录 |
| 401 | BAD_CREDENTIALS | 登录或当前密码不正确，统一提示 |
| 429 | RATE_LIMITED | 15 分钟后重试，不自动重放注册 |
| 409 | ACCOUNT_CHANGED | 清空当前页面、停止同步并重新确认身份 |
| 409 | QUOTA_EXCEEDED | 查看个人容量与来源额度 |
| 429 | TASK_LIMITED | 等待现有任务完成，恢复预览需完成确认或等待副本过期 |
| 503 | CAPACITY_EXCEEDED | 联系维护者处理存储；任务页保留具体失败原因 |

注册返回的约束错误不包含邀请码持有者资料。维护命令见 [账号维护手册](../runbooks/accounts.md)，决策见 [ADR 0014](../decisions/0014-p1-08-independent-accounts.md)。
