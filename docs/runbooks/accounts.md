# 账号、邀请码与容量维护

P1-08 默认关闭注册。首次升级之前使用完整服务器快照保留数据库、文件、暂存和配置；V11 与新后端一起部署。旧预置账号精确绑定原 ID，原密码不变；绑定后环境 `ACCOUNT_USERNAME / ACCOUNT_PASSWORD_HASH` 只作为首次初始化配置，改变它们不再修改任何现有密码或创建新空间。新账号由邀请码注册，不要手动改预置绑定或清空迁移表。

## 受控维护入口

维护者需有部署主机和数据库访问权限。工具不启动 Web、不运行 worker、不输出密码，也不把令牌或 hash 存入审计。成功操作在 `account_admin_audit` 记录 action、目标 UUID、时间。

使用生产 Compose 时，从仓库根目录执行，保持真实 `--env-file` 和 `-p` 项目名一致：

```bash
# 有效 7 天，可注册 1 个账号。只在终端显示一次令牌；另记录 Invite ID 以便撤销。
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec backend \
  java -Dloader.main=com.interviewflashcards.tools.AccountAdmin -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher invite 7 1

# 撤销此邀请的剩余额度，已注册账号和学习资料不受影响。
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec backend \
  java -Dloader.main=com.interviewflashcards.tools.AccountAdmin -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher revoke INVITE_UUID

# 两次交互输入新密码，不回显。最后参数为精确登录名，旧名字不能擅自改大小写。
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec -it backend \
  java -Dloader.main=com.interviewflashcards.tools.AccountAdmin -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher reset EXACT_USERNAME
```

生成命令接受 1～365 天、1～100 个名额。请私下交付令牌，不放在公开页面、命令行参数、日志、截图或仓库。忘记密码无公开重置 API，维护者应先线下确认身份，再使用 reset。完成后该用户所有旧会话下一次请求立即失效；无需重启，不改变 ID、学习数据及其他账号密码。`reset` 必须用有 Console 的交互终端，不能通过管道、脚本参数或带 `</dev/null` 的命令简写输入密码。

源码模式先 `mvn -B -ntp -DskipTests package`，设置 `DATABASE_URL / DATABASE_USERNAME / DATABASE_PASSWORD` 后运行同样的 loader 命令，把 classpath 改为 `backend/target/app.jar`。工具默认使用数据库默认 schema；隔离测试库可设置 `DATABASE_SCHEMA` 为已迁移的 schema。Windows 可使用 `java '-Dloader.main=com.interviewflashcards.tools.AccountAdmin' -cp backend/target/app.jar org.springframework.boot.loader.launch.PropertiesLauncher invite 7 1`。

## 开放注册与配置

P1-11 升级须同时发布 V14、后端与前端。“个人中心 → 我的邀请码”默认显示未授权，注册账号不会自动获得发码权。维护者可使用以下受控命令（替换精确登录名与成功邀请总额度）：

```bash
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec backend \
  java -Dloader.main=com.interviewflashcards.tools.AccountAdmin -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher permission EXACT_USERNAME true 3

# 暂停发码及尚未消费的个人码，不影响已注册账号；保留历史成功计数。
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec backend \
  java -Dloader.main=com.interviewflashcards.tools.AccountAdmin -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher permission EXACT_USERNAME false 3
```

总额度是累计成功人数而非新增人数，支持 0～10000；调小到低于已成功数时剩余为 0，不返还历史额度。重新授权不会重置计数。命令记录 action 与目标 UUID，不记录 token 或密码。个人码每枚 7 天、1 人，同时最多一枚有效码。丢失明文或生成响应时先刷新状态，确认撤销旧码，再单独生成；维护码的原命令继续可用。

启用前明确设置 `REGISTRATION_MAX_ACCOUNTS`（正整数，默认 100），P1-12 起它按 `registration_use` 加 `admin_account_allocation` 统计全站累计新增账号数，含已有维护邀请及管理建号；与每用户成功邀请额度同时生效。预置账号不占该名额。设置 `REGISTRATION_ENABLED=false` 或上限已满会阻止预校验、发码与最终注册，已有账号仍可登录。配置变更需重建后端加载环境，不能只更新 `.env`。两份 Compose 和环境示例均提供入口，详细错误与并发规则见 [P1-11 API](../api/personal-invitations.md)。

生成邀请后在 `.env.production` 设置 `REGISTRATION_ENABLED=true` 并重建后端容器以加载新环境。关闭时设 false 并重建，已注册用户仍可登录。配置示例和两份 Compose 均提供开关与配额；具体默认值、字节计量和错误码见 [API 契约](../api/registration-and-accounts.md)。按现有小范围部署默认每用户 512 MiB / 20 个来源、2 个导入和 2 个备份/恢复，全站 5 GiB / 20 个任务并保留 512 MiB 可用空间；开放邀请前核对主机、容器卷及 PostgreSQL 剩余容量，再决定是否调整。

来源、个人文件、副本和全站任务限制都在服务端执行。我的资料页面显示当前剩余额度；等待确认的恢复也占任务名额，确认完成后释放，临时副本 7 天清理。容量保护包含未引用旧文件，不能通过删业务行获得虚假的全站余量。失败任务可重建；若 503 CAPACITY_EXCEEDED，先核对磁盘和旧资源，保留完整快照，不要直接删除 files 卷。

登录按账号和可信来源限流，注册/改密按可信来源限流，重启不清零。生产只能让 Nginx 访问后端，代理覆盖 X-Forwarded-For，后端原生解析受信任代理。不要将 8080 发布公网；改变代理链时重新核对 Tomcat trusted/internal proxy 配置。限制发生后等待窗口自然过期，不建议删除限流表绕过。

## 升级检查

升级后先验证旧账号登录与原资料，改密再重启，确认原 ID 和新密码保持。再用一枚一次性邀请注册新用户，核对两个账号的来源/卡片/同步/备份/恢复隔离；A 留下离线笔记和评分时在另一标签页切 B，A 标签页应停止并重新登录，B 不出现 A 内容，切回 A 后原队列仍在。实体手机和电脑各验证一次。仓库自动化及浏览器演练见 [验收记录](../fixtures/p1-08/README.md)。

回退要停止设备写入，整体恢复升级前数据库、files、temporary-files 和配置，再启动匹配的旧镜像。旧后端仍有启动覆盖 hash 的逻辑，不支持仅回退镜像继续写 V11 数据库。个人学习 ZIP 不含账号和邀请码，不能用于回退账号迁移。


## P1-12 管理员初始化与用户管理

V15、新后端和新前端一起发布。迁移不会自动提升任何现有账号；原 ID、密码、预置绑定和学习数据保持。部署前保留完整服务器快照，先在隔离库验证。首个管理员使用独立用户名和独立密码，在受控终端执行：

```bash
# 两次交互输入符合规则的密码，终端不回显；不要把密码写入命令参数。
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec -it backend \
  java -Dloader.main=com.interviewflashcards.tools.AccountAdmin -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher create-admin NEW_USERNAME

# 明确授权现有账号；保留原密码、ID 和学习空间，撤销旧会话。
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec backend \
  java -Dloader.main=com.interviewflashcards.tools.AccountAdmin -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher role EXACT_USERNAME ADMIN

# 回收角色，拒绝移除最后一个启用管理员；旧会话立即失效。
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec backend \
  java -Dloader.main=com.interviewflashcards.tools.AccountAdmin -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher role EXACT_USERNAME USER
```

源码方式沿用本页 loader 命令，Windows 的 `-Dloader.main=...` 使用单引号。`create-admin` 只新增；重复执行会拒绝，不改变原密码。不要通过更换 `ACCOUNT_USERNAME` 来创建管理员。显式绑定前线下核对准确账号。离线初始化管理员可在注册关闭时执行，仍读取 `REGISTRATION_MAX_ACCOUNTS`（缺省 100），并占用一份永久全站名额；运行工具时使用与服务一致的环境。ADMIN 的个人邀请码仍需单独授权，不自动放开发码。

管理员登录后进入顶部“管理后台”。用户管理支持昵称/用户名、启停及邀请资格筛选、分页、账号与存储元数据、最近登录和审计。按实际学习账号情况新增普通用户，初始密码线下交付；用户首次登录只允许改密，随后重新登录。后台新增普通用户受全站注册开关与累计上限限制，默认关闭时页面建号也会拒绝。权限管理展示当前全站环境限制，页面不提供无法保存的开关。

对普通用户可启停、发起临时密码重置、开放/回收邀请、调总额度、查看及撤销未消费邀请。操作前核对目标和确认范围；成功计数不因调低或重新授权清零。停用及回收邀请会撤销未消费个人码，启用/重新授权不恢复旧码；已注册好友账号不受影响。停用不删除资料。启用、停用、重置、改密及维护角色变更不会恢复旧会话；用户需重新登录。后台不能管理管理员凭据或授予管理角色。

网络中断后先按用户名查询或刷新详情核对结果，不自动重放。建号只有同一 operationId 和原始内容的显式重试返回原账号；其余操作的旧版本会被拒绝。管理请求不进入学习离线队列。页面每 30 秒及激活时刷新元数据，表单输入保留；失败清除管理数据，权限回收下次请求生效。

`account_admin_audit` 记录操作者/MAINTENANCE、目标、非敏感前后值、时间和成功/失败结果。不包含密码、hash 或邀请码明文。个人学习 ZIP 不包含账号/管理数据，不能恢复权限、返还名额或解除停用。知识库和 Dashboard 当前标明 P1-13 / P1-14 待实现，时长尚未采集。完整契约见 [管理 API](../api/admin-accounts.md)，验证见 [P1-12 验收](../fixtures/p1-12/README.md)。
