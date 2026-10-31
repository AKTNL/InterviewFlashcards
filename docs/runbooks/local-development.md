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

编辑 `deploy/.env`，填写自行生成的非空 `POSTGRES_PASSWORD`。文件已忽略，不提交版本库；其他值可以保留示例。没有提供可直接投入使用的默认数据库密码。

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml up --build -d --wait
```

打开 [应用页面](http://127.0.0.1:8088/) 和 [健康检查](http://127.0.0.1:8088/api/health)。页面应显示“服务与数据库连接正常”和服务端学习日期。Web 代理 `/api/` 到内部后端，后端不向主机暴露端口；数据库仅绑定本机 55432。启动先等待数据库健康，再运行 Flyway，最后开放 Web。

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml ps
docker compose --env-file deploy/.env -f deploy/compose.yml logs --tail=100 backend
docker compose --env-file deploy/.env -f deploy/compose.yml down
docker compose --env-file deploy/.env -f deploy/compose.yml up -d --wait
```

`down` 保留命名卷，随后启动应保留安装记录、迁移历史和文件。`database` 挂载 PostgreSQL 18 的 `/var/lib/postgresql`；`files` 与 `temporary-files` 分别挂载 `/data/files`、`/data/tmp`。不要在保留数据时使用 `down -v`。修改已有数据库的环境密码不会更改数据库内密码；更换凭据需要显式的数据库密码更新。

本环境是回环地址上的 HTTP 开发环境。生产 HTTPS、域名和 Secure Cookie 属于 P0-13；当前不能视为公网发布配置。

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

后端额外设置见 `backend/.env.example`。未提供数据库凭据时启动失败；不回退到内存库。源码模式的文件/临时目录默认 `backend/data/files`、`backend/data/tmp`，已经忽略；实际文件上传和访问在 P0-03 实现。学习时区固定为 `Asia/Shanghai`，日志、JSON 时间戳和数据库连接使用 UTC。

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

集成测试使用真实 PostgreSQL，不替换为 H2。测试数据库账号需要创建/删除 schema 权限；每次使用随机空 schema，结束删除该 schema。测试会检查空库迁移、完整启动、HTTP 健康与安全错误、UTC、应用重启后安装 ID/数据保持、迁移不重复执行。未设置环境不会静默跳过。

CI 配置在 `.github/workflows/ci.yml`：前端类型/测试/构建；后端 PostgreSQL 集成测试/构建；完整 Compose 构建、同源健康检查及删除重建容器后的数据库/文件持久化检查。CI 数据库凭据仅用于一次性测试服务。

## 5. 排查

| 现象 | 检查 |
| --- | --- |
| Compose 提示密码变量为空 | 先填写 `deploy/.env`，每条命令带 `--env-file deploy/.env` |
| Docker 无法连接引擎 | 启动 Docker Desktop，确认 Linux 容器模式及当前 context |
| Windows JDK 报 `Unable to establish loopback connection` / `Invalid argument: connect` | 建立较短的目录，例如 `D:\InterviewFlashcards\tmp`，设置 `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\InterviewFlashcards\tmp'` 后重试。Windows JDK 的 socket 临时目录独立于 `java.io.tmpdir`；本机集成测试使用此参数。Linux/Compose 不需要此参数 |
| 应用启动时数据库认证失败 | 用户、密码、数据库名称与已有卷初始化时是否一致 |
| 页面提示服务不可用 | 后端是否启动，8080 是否被其他进程使用，查看请求编号对应日志 |
| 端口被占用 | 修改 `.env` 的 `WEB_PORT`、`DB_PORT`；源码后端 `SERVER_PORT` 修改后同步改 Vite proxy |
| 迁移校验失败 | 不修改已执行的迁移；通过新增 V2、V3 等文件演进，不清空数据库绕过 |
| 容器构建下载 Maven 依赖失败 | 默认使用官方 `https://repo1.maven.org/maven2`；可在 `deploy/.env` 设置 `MAVEN_MIRROR_URL` 为当前网络可访问的可信 Maven 仓库，再执行 `up --build`。容器构建缓存 Maven 依赖；此设置不修改主机 Maven 配置 |

P0-01 PDF 验证工具仍独立运行，见 `tools/pdf-probe/README.md`。
