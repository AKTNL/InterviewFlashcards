# 本地启动与验证

## 1. 环境

- 全容器启动：Docker Engine/Desktop（Linux 容器）与 Docker Compose v2，确保 Docker 引擎已启动。
- 源码开发：Java 21、Maven 3.9.15、Node 24.13.0、npm 11；PostgreSQL 18（可只使用 Compose 的 db 服务）。
- 首次构建需要访问 npm、Maven Central 和容器镜像仓库。前端提交了 `package-lock.json`，使用 `npm ci`；Spring Boot 3.5.13 BOM 固定后端依赖。

从仓库根目录执行以下命令。

## 2. 一次启动完整环境

```powershell
Copy-Item deploy/.env.example deploy/.env
```

编辑 `deploy/.env`，填写自行生成的非空 `POSTGRES_PASSWORD`、个人 `ACCOUNT_USERNAME` 和 BCrypt `ACCOUNT_PASSWORD_HASH`。文件已忽略，不提交版本库；其他值可以保留示例。没有默认数据库密码或登录密码。

先构建后端，再在交互式终端运行离线工具生成 hash（不需要数据库环境）：

```powershell
cd backend
mvn -B -ntp -DskipTests package
cd ..
java '-Dloader.main=com.interviewflashcards.tools.PasswordHash' -cp backend/target/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

两次输入至少 12 个字符、最多 72 个 UTF-8 字节的密码；终端不回显密码，只输出 BCrypt hash。将 hash 放在 `.env` 的单引号中，例如 `ACCOUNT_PASSWORD_HASH='$2a$12$…'`，保留实际输出的完整 60 个字符，防止 Compose 将 `$` 当成变量。也可以使用可信密码管理器生成 BCrypt cost 10～16 的 hash。不要将密码作为命令行参数或写入版本库。

只使用 Docker 时，可以先执行 `docker compose --env-file deploy/.env -f deploy/compose.yml build backend`，再用 `docker run --rm -it --entrypoint java interview-flashcards-backend '-Dloader.main=com.interviewflashcards.tools.PasswordHash' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher` 生成。构建前先在 `.env` 填写账号，并为 hash 字段放置任意非空占位文本以通过 Compose 配置插值；启动前必须替换为真实合法 hash。

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml up --build -d --wait
```

打开 [应用页面](http://127.0.0.1:8088/) 和 [健康检查](http://127.0.0.1:8088/api/health)。首页应显示“服务与数据库连接正常”和服务端学习日期；使用配置账号登录“我的资料”，上传 PDF 并查看原文。Web 代理 `/api/` 到内部后端，后端不向主机暴露端口；数据库仅绑定本机 55432。启动先等待数据库健康，再运行 Flyway，最后开放 Web。

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml ps
docker compose --env-file deploy/.env -f deploy/compose.yml logs --tail=100 backend
docker compose --env-file deploy/.env -f deploy/compose.yml down
docker compose --env-file deploy/.env -f deploy/compose.yml up -d --wait
```

`down` 保留命名卷，随后启动应保留安装记录、迁移历史和文件。`database` 挂载 PostgreSQL 18 的 `/var/lib/postgresql`；`files` 与 `temporary-files` 分别挂载 `/data/files`、`/data/tmp`。不要在保留数据时使用 `down -v`。修改已有数据库的环境密码不会更改数据库内密码；更换凭据需要显式的数据库密码更新。

本环境是回环地址上的 HTTP 开发环境。公网必须使用 HTTPS，并设置 `SESSION_COOKIE_SECURE=true`；生产使用独立的 deploy/compose.production.yml 和证书配置，见 [HTTPS 发布、维护与回退](release.md)。不要将当前 HTTP 配置直接暴露公网。

## 3. 前后端源码开发

先用上一节的配置启动数据库：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml up -d --wait db
```

在后端终端设置环境（Spring 不自动读取 `.env`）：

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55432/interview_flashcards'
$env:DATABASE_USERNAME = 'flashcards'
$env:DATABASE_PASSWORD = Read-Host '输入 deploy/.env 中的数据库密码'
$env:ACCOUNT_USERNAME = '你的预置账号'
$env:ACCOUNT_PASSWORD_HASH = Read-Host '粘贴完整 BCrypt hash'
# 数据库名称、用户和端口应与 deploy/.env 一致。
cd backend
mvn -B -ntp spring-boot:run
```

另外开启前端终端：

```powershell
cd frontend
npm.cmd ci --no-audit --no-fund
npm.cmd run dev
```

打开 [开发页面](http://127.0.0.1:5173/)。Vite 将 `/api/` 代理到 `127.0.0.1:8080`，浏览器仍使用同源请求。若已开启完整 Compose，源码开发只需避免端口占用；完整栈的后端端口没有暴露，因此不会占用本机 8080。

macOS/Linux 使用同名的 `export DATABASE_URL=...`、`export DATABASE_USERNAME=...`、`export DATABASE_PASSWORD=...`，前端使用 `npm` 代替 `npm.cmd`。

后端额外设置见 `backend/.env.example`。缺少数据库凭据或预置账号/hash 时启动失败；不回退到内存库或默认测试账号。源码模式的文件/临时目录默认 `backend/data/files`、`backend/data/tmp`，已经忽略；两个目录必须分离，multipart 暂存也使用配置的绝对临时目录。学习时区固定为 `Asia/Shanghai`，日志、JSON 时间戳和数据库连接使用 UTC。

P1-08 起预置身份只绑定一次。升级时保留原账号 ID、密码和全部学习数据；重启时不再应用环境中的 hash，也不会因修改 `ACCOUNT_USERNAME` 创建或接管新账号。修改密码使用应用中的“账号安全”，忘记密码使用受控维护命令，详见 [账号与邀请码维护](accounts.md)。默认闲置 30 分钟会话过期，可设置 `SESSION_TIMEOUT`。单台设备退出不影响其他设备；修改或维护重置密码会撤销该账号所有旧会话。

单 PDF 最大 50 MiB，Nginx/multipart 请求最大 51 MiB。重复选择同一文件返回已有来源和解析任务。上传、名称编辑和解析重试先写入 IndexedDB，再附操作 ID/代次/版本提交；断网或响应丢失后保留原操作重试，“保存与同步”显示服务端确认状态。清理站点数据会删除本地待同步内容，请先处理或保留副本。上传保存后自动后台解析；“我的资料 → 查看解析任务/章节与草稿”显示阶段、错误、数量核对和完整候选。重启中断的任务保留证据，显式重试后继续；READY 任务或已有草稿不会重新解析覆盖。原文件与按需页面原图均通过鉴权 API 读取。接口变化见 [写入与同步协议](../api/mutations-and-sync.md) 和 [解析任务与草稿](../api/imports-and-drafts.md)。

逐章检查从资料的章节列表进入，支持问题/内容/追问/来源边界编辑及合并、拆分和排除；填写原因后保存到待同步队列。收到服务端确认后可跨设备继续检查。“继续上次检查”恢复位置。确认前核对有效题数和待检查数，有标记需明确勾选。只确认当前章，其他章保持草稿；确认后可进入本章正式题库阅读，返回原章修正现有卡片而不重建 ID。放弃剩余草稿为单章操作，需要另行确认。接口和错误处理见 [逐章检查 API](../api/chapter-review.md)。

题库入口位于顶部“题库”，可按题库/模块/章节及题目关键词检索，再筛选未学、已学、到期、薄弱、收藏或待检查。详情先展示问题，完整答案和提示按需展开；笔记收藏、技术纠错、版本标签和内容/标记修改仍经“保存与同步”确认，不改动现有学习日期。点击任意来源范围进入内置 PDF 阅读器，支持物理页跳转和高亮，手机宽度同样适屏；读取失败可重试或直接打开原 PDF。首次安装增加固定版本 PDF.js，需执行 `npm ci`；生产构建会一并复制同版本的 cMap、字体和 wasm 至 `dist/pdfjs/`。部署时使用更新后的 `deploy/nginx.conf`，确保 `.mjs` worker 的 JavaScript MIME、wasm MIME 和静态资源 404 生效。接口见 [题库与来源阅读 API](../api/card-library.md)。

题库详情的“主动回忆与评分”进入单题练习，题库结果的“练习本页”进入手动批次。先回忆再展开完整答案，才可三档自评；服务端确认后显示下次日期并前进，原操作在短时断网/刷新后继续等待收据。不会可自动追加一次可跳过的重练，同日反复练习不会升级阶段或重复计数；可撤销最近且无后续写入的评分，重新学习需明确确认。跨天会话只读，请重新进入练习。冲突需核对双方内容，不能自动换版本重放评分。每日计划与学习设置现已实现，见下。接口见 [学习与评分 API](../api/learning.md)。

登录后的首页为“今日学习”，先进入“学习设置”确认新题量、复习批量和已确认章节。建议 5/20，可设新题 0、暂停章节或填写可选参考时长。首页显示全部到期数、今日完成及剩余额度，支持混合/只复习/只学新题。返回首页可暂离，通过“接着上次继续”恢复同一条目；切换设备以已保存记录为准。范围暂停、其他会话已处理及额度变化都会重新核对队列；跨天重新生成今日计划，不重置逾期日期。轮末回顾展示本轮新学、复习和仍需关注的卡片，可回首页继续下一批。设置冲突可下载两侧副本，核对后放弃旧待同步操作、重新读取并保存。接口见 [每日学习 API](../api/daily-learning.md)。

默认算法为 `rules-v1`；可通过 Spring 属性 `app.review.algorithm-version` 和 `app.review.success-intervals` 配置五阶段间隔。改变默认 `[1,3,7,14,30]` 时必须更换算法版本名，历史事件与到期日期保持原样。

## 4. 检查

前端（在 `frontend/`）：

```powershell
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
```

后端纯单元/接口契约测试（在 `backend/`，不需要数据库）：

```powershell
mvn -B -ntp test
```

后端集成测试及构建（先设置测试 PostgreSQL 的三个 `DATABASE_*` 环境变量）：

```powershell
mvn -B -ntp -Pintegration verify
```

集成测试使用真实 PostgreSQL，不替换为 H2。测试数据库账号需要创建/删除 schema 权限；每次使用随机空 schema，结束删除该 schema。检查包括 V1～V11 迁移、完整启动、HTTP/UTC、安装和用户 ID 保持、CSRF/会话、越权 ID、归属外键、并发文件去重、PDF/图片 Range、应用重启后原文件读取，以及幂等摘要/收据、双会话版本冲突、业务/收据失败回滚、代次隔离、真实 PDF 草稿、保存中途回滚、提取证据复用、重试幂等与编辑保护；另验证合并/拆分来源、数量调整、检查位置重启恢复、带标记确认、重复/并发确认、确认中途原子回滚、已导入卡片及复习状态保持和放弃范围，以及全题库分页/字面搜索/全部状态筛选、笔记收藏并发与回滚、纠错标签及复习日期保留。P0-11 增加 ZIP 损坏/路径/配额、全数据及资源往返、逾期日期、恢复会话、跨账号强制归属、预览过期、事务回滚和旧代次拒绝。后端单元测试直接读取仓库 docs 的真实 PDF，逐题对比 P0-01 边界、全文哈希、追问和标记；从仓库检出目录运行，不能只复制 backend 后测试。测试账号由启动参数提供，不使用实际账号环境；未设置数据库环境不会静默跳过。

CI 配置在 `.github/workflows/ci.yml`：前端类型/测试/构建；后端 PostgreSQL 集成测试/构建；完整 Compose 构建、同源健康检查及删除重建容器后的数据库/文件持久化检查。CI 数据库凭据仅用于一次性测试服务。

## 5. 排查

| 现象 | 检查 |
| --- | --- |
| Compose 提示密码变量为空 | 先填写 `deploy/.env`，每条命令带 `--env-file deploy/.env` |
| Docker 无法连接引擎 | 启动 Docker Desktop，确认 Linux 容器模式及当前 context |
| Windows JDK 报 `Unable to establish loopback connection` / `Invalid argument: connect` | 建立较短的目录，例如 `D:\InterviewFlashcards\tmp`，设置 `$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Djdk.net.unixdomain.tmpdir=D:\InterviewFlashcards\tmp -Djava.io.tmpdir=D:\InterviewFlashcards\tmp'` 后重试。socket 临时目录独立于 `java.io.tmpdir`；临时文件权限受限时同样使用仓库内可写目录。Linux/Compose 不需要此本机路径 |
| Windows 打包提示无法重命名 app.jar | 先停止通过该 jar 启动的本地后端，再重新 package/verify |
| 预置账号配置无效或无法登录 | 核对用户名、完整 BCrypt hash 与对应密码；`.env` 的 hash 使用单引号，源码环境用 `Read-Host` 粘贴；本地 HTTP 不开启 Secure Cookie |
| 应用启动时数据库认证失败 | 用户、密码、数据库名称与已有卷初始化时是否一致 |
| 页面提示服务不可用 | 后端是否启动，8080 是否被其他进程使用，查看请求编号对应日志 |
| 端口被占用 | 修改 `.env` 的 `WEB_PORT`、`DB_PORT`；源码后端 `SERVER_PORT` 修改后同步改 Vite proxy |
| 迁移校验失败 | 不修改已执行的迁移；通过新增 V2、V3 等文件演进，不清空数据库绕过 |
| 容器构建下载 Maven 依赖失败 | 默认使用官方 `https://repo1.maven.org/maven2`；可在 `deploy/.env` 设置 `MAVEN_MIRROR_URL` 为当前网络可访问的可信 Maven 仓库，再执行 `up --build`。容器构建缓存 Maven 依赖；此设置不修改主机 Maven 配置 |

P0-01 PDF 验证工具仍独立运行，见 `tools/pdf-probe/README.md`。

## P0-10 同步与冲突操作

登录后“保存与同步”始终可见，总状态包含本地待确认操作、失败及服务端评分候选。页面激活、重新联网和手动“刷新与重试”会检查服务端；活跃页每 30 秒检查修订，无变化时不重新读取完整快照。网络失败保留内容并退避；登录失效后重新登录原账号，旧操作 ID 不变。不要通过清理站点数据处理同步失败。

笔记、来源名称、草稿 EDIT 和学习设置冲突可展开核对、下载双方副本，明确选择本地重新保存或放弃本地操作。若远端又有新修改，界面先更新核对内容，需再次核对；合并/拆分/确认等结构操作须刷新清单重新选择。编辑中的本地内容不会被远端刷新自动覆盖。

不同设备对同一复习基线的相同评分只确认已完成；不同评分出现“评分冲突待处理”，候选不改变进度。任一设备可以接受当前结果，或明确使用候选更正。更正仅允许原评分仍是最新且处于同一服务端学习日；已有后续学习或跨日时请接受当前结果，需要再练习则主动新进入。更正保留原事件、替代事件和证据，不增加新学数。详情见[跨设备同步与评分冲突 API](../api/cross-device-sync.md)。

## P0-11 备份与恢复操作

1. 登录后打开顶部“备份与恢复”。先确认“保存与同步”中的内容已获服务端确认；备份不会包含尚在其他设备或 IndexedDB 中的离线修改。
2. 点击“导出当前完整数据”，可离开继续学习，稍后回来下载 ZIP。ZIP 包含备份时的学习空间、PDF 与图片，不包含账号密码、Cookie 或部署密钥。请下载长期保存；服务端临时副本只保留 7 天。
3. 恢复前建议先导出并下载当前空间。选择备份 ZIP（最多 50 MiB），“上传并预检”不会立即替换。等待检查通过，核对备份时间、当前/备份数量及完整替换范围。
4. 勾选明确确认并点击“确认替换并恢复”。如果预览后继续学习或另一设备保存了内容，刷新影响预览并重新确认。成功后原 dueDate 与历史学习日保留，逾期题仍然逾期。备份中未完成的解析任务需到资料页显式重试。
5. 其他设备联网或激活后获取新数据；旧待同步操作保留为“待处理”。下载/核对本地内容后决定是否在新空间重新编辑，不直接重放旧请求，也不要清理站点数据来消除提示。

恢复失败时原空间可继续使用。文件损坏、缺失或版本不兼容时重新选择完整 ZIP；服务重启中断的导出需重建，未提交的恢复需刷新预览重新确认。任务结果在页面重开后仍可查询。后台日志以任务 ID 和错误类型定位，不打印包内正文或凭据。恢复时必须保留当前数据库卷、文件卷和临时卷；不要手动清空任一卷。

当前使用单 JVM worker，导出和预检不占用用户写锁进行压缩/解压；实际替换短事务会等待该用户已开始写入完成。启动清理遗留的备份解压临时目录，按小时清理超过 7 天的上传/下载副本；旧业务资源与失败准备产生的未引用文件暂保留，自动资源垃圾回收和自动备份仍在后续运维范围。限制、错误码和完整字段见[备份恢复 API](../api/backups-and-restores.md)。

## P0-12 题库数据操作与手机阅读

学习设置 → “题库删除与学习进度重置”，先选择题库/操作并读取影响计数。建议先导出并下载完整备份。确认前需处理本地未同步操作，核对预览并勾选明确确认；预览过期须重新读取，不直接重试旧版本。等待同步收据后才表示完成，失败到“保存与同步”核对；过期预览操作先明确放弃，再重新预览。

删除清除该题库全部内容及历史，保留资料中的原 PDF/图片，解析任务显示已删除，可显式“重试解析”重新导入；重置只清除该题库学习历史及复习安排，回到未学，保留笔记和收藏。相关混合批次整体失效，其他题库历史保留；设备重新联网后刷新并新进入学习，旧离线评分保留核对且不能覆盖重置结果。

手机阅读支持长内容换行、宽表格局部滚动、原图/来源 PDF 查看与 PDF 缩放。设置、资料改名、卡片/草稿未保存时离开会提示；本地待同步状态不能视为服务端确认，退出失败会显示错误。已完成 360px/桌面浏览器与真实 PDF 演练，实体设备验收留 P0-13。验证明细和隔离脚本见 [P0-12 验收记录](../fixtures/p0-12/README.md)。
