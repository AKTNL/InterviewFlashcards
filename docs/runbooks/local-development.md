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

本环境是回环地址上的 HTTP 开发环境。公网必须使用 HTTPS，并设置 `SESSION_COOKIE_SECURE=true`；生产域名、证书和完整发布仍属于 P0-13。不要将当前 HTTP 配置直接暴露公网。

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

同名账号重启时更新配置 hash、保留原有用户 ID 和数据。修改 `ACCOUNT_USERNAME` 会创建不同的个人空间，不会重命名原账号。轮换密码时生成新 hash、更新配置并重启后端；进程内会话失效，设备重新登录。默认闲置 30 分钟会话过期，可设置 `SESSION_TIMEOUT`。单台设备退出不影响其他设备。

单 PDF 最大 50 MiB，Nginx/multipart 请求最大 51 MiB。重复选择同一文件返回已有来源和解析任务。上传、名称编辑和解析重试先写入 IndexedDB，再附操作 ID/代次/版本提交；断网或响应丢失后保留原操作重试，“保存与同步”显示服务端确认状态。清理站点数据会删除本地待同步内容，请先处理或保留副本。上传保存后自动后台解析；“我的资料 → 查看解析任务/章节与草稿”显示阶段、错误、数量核对和完整候选。重启中断的任务保留证据，显式重试后继续；READY 任务或已有草稿不会重新解析覆盖。原文件与按需页面原图均通过鉴权 API 读取。接口变化见 [写入与同步协议](../api/mutations-and-sync.md) 和 [解析任务与草稿](../api/imports-and-drafts.md)。

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

集成测试使用真实 PostgreSQL，不替换为 H2。测试数据库账号需要创建/删除 schema 权限；每次使用随机空 schema，结束删除该 schema。检查包括 V1～V4 迁移、完整启动、HTTP/UTC、安装和用户 ID 保持、CSRF/会话、越权 ID、归属外键、并发文件去重、PDF/图片 Range、应用重启后原文件读取，以及幂等摘要/收据、双会话版本冲突、业务/收据失败回滚、代次隔离、真实 PDF 草稿、保存中途回滚、提取证据复用、重试幂等与编辑保护。后端单元测试直接读取仓库 docs 的真实 PDF，逐题对比 P0-01 边界、全文哈希、追问和标记；从仓库检出目录运行，不能只复制 backend 后测试。测试账号由启动参数提供，不使用实际账号环境；未设置数据库环境不会静默跳过。

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
