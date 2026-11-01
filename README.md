# InterviewFlashcards

面向 Java 后端面试的卡片学习网站：将 PDF 资料按章节整理为保留完整答案与来源的题库，通过主动回忆、三档自评和间隔复习持续学习，支持手机与电脑接续进度。

**基本流程：上传 PDF → 检查并确认章节 → 设置学习范围 → 每日学习与复习 → 同步和备份。**

## 主要功能

- **PDF 导入与检查**：后台解析、失败重试、章节数量核对与异常集中处理；支持编辑、合并、拆分、排除候选，并排对照原文，逐章确认后即可学习。
- **题库与阅读**：按题库、模块、章节、关键词和学习状态筛选；完整答案、追问、笔记、收藏、标签与技术纠错，支持 PDF 物理页定位、字号、阅读主题、快捷键和返回位置恢复。
- **每日学习**：先回忆再展开答案，按“不会 / 模糊 / 记住了”自评；支持每日新题量、复习批量、章节范围、会话接续、评分撤销、有限重练及个人复习参数。
- **进度与趋势**：今日完成情况、按周学习记录、遗忘题排序和章节变化；多题库各自保留内容与进度，可核对同源副本和重复标题。
- **跨设备同步**：IndexedDB 保存待同步内容，提供幂等收据、断网重试、增量刷新和冲突核对；恢复后的旧队列保留供处理。
- **账号与共同学习**：独立账号、密码管理、受控邀请码注册、一次性题库副本分享，以及由成员自愿授权学习摘要的小组。
- **管理与运维**：管理员账号与权限、平台知识库发布和领取、使用统计 Dashboard、个人完整 ZIP 备份与替换恢复，以及服务端一致快照、自动保留策略和隔离恢复演练。

## 当前进度

截至 **2026-11-01（Asia/Shanghai）**，P0-01～P0-13、P1-01～P1-14 均已完成仓库实现与本地验收。公网最近一次记录为 P1-11 / 数据库 V14；当前仓库数据库迁移为 V1～V19。

| 范围 | 交付内容与验收记录 | 公网状态 |
| --- | --- | --- |
| P0-01～P0-13 | PDF 导入、学习、同步、备份、响应式布局与部署；[任务与验收索引](docs/开发文档.md) | 已发布，M5 已签收；实体手机、双端同步、续期及恢复已验收 |
| P1-01 | [阅读设置、快捷键与返回位置](docs/fixtures/p1-01/README.md) | 待发布 |
| P1-02 | [相似题提示、异常集中处理与原文对照](docs/fixtures/p1-02/README.md) | 待发布 |
| P1-03 | [按周学习记录、遗忘排序与章节变化](docs/fixtures/p1-03/README.md) | 待发布 |
| P1-04 | [个人复习参数与效果评估](docs/fixtures/p1-04/README.md) | 待发布 |
| P1-05 | [变更通知与修订增量](docs/fixtures/p1-05/README.md) | 待发布 |
| P1-06 | [多个独立题库管理](docs/fixtures/p1-06/README.md) | 待发布 |
| P1-07 | [服务端自动备份、保留策略与恢复演练](docs/fixtures/p1-07/README.md) | 线上定时器尚未安装或启用 |
| P1-08～P1-10 | [独立账号与注册](docs/fixtures/p1-08/README.md)、[题库副本分享](docs/fixtures/p1-09/README.md)、[学习小组](docs/fixtures/p1-10/README.md) | 已发布；新功能实体设备操作待复验 |
| P1-11 | [个人邀请码与分步注册](docs/fixtures/p1-11/README.md) | 已发布；注册保持关闭，个人邀请权限待维护者授权 |
| P1-12 | [管理员账号、用户管理与权限配置](docs/fixtures/p1-12/README.md) | 待发布，线上账号权限未调整 |
| P1-13 | [平台知识库导入、发布与独立副本领取](docs/fixtures/p1-13/README.md) | 待发布 |
| P1-14 | [管理 Dashboard 与使用统计](docs/fixtures/p1-14/README.md) | 待发布 |

发布证据：[P0 公网部署](docs/fixtures/p0-13/public-deployment-2026-11-01.md) · [ZIP 恢复与旧设备重连](docs/fixtures/p0-13/public-restore-2026-11-01.md) · [P1-08～P1-10 升级](docs/fixtures/p1-10/public-deployment-2026-11-01.md) · [P1-11 升级](docs/fixtures/p1-11/public-deployment-2026-11-01.md)。

## 技术栈与目录

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Vue Router、Pinia、PDF.js；CI 使用 Node 24.13.0 |
| 后端 | Java 21、Spring Boot 3.5.13、Spring Security、JPA / JDBC、Apache PDFBox 3.0.5 |
| 数据与同步 | PostgreSQL 18.3、Flyway、IndexedDB 待同步队列 |
| 部署与运维 | Docker Compose v2、Nginx、HTTPS；Python / Bash 备份工具及 systemd 定时器 |
| 验证 | Vitest、JUnit、真实 PostgreSQL 集成测试、GitHub Actions |

```text
frontend/       Vue 页面、组件、状态管理、API 与同步队列
backend/        Spring Boot 业务模块、Flyway 迁移与测试
deploy/         本地 / 生产 Compose、Nginx、环境示例及 systemd 配置
docs/           设计、开发任务、API、决策、验收证据与运维手册
tools/          PDF 回归验证、发布检查、一致快照与自动备份工具
```

后端采用单进程模块化应用，数据库由 Flyway 演进，JPA 只校验结构。Web 与 API 同源，来源文件通过鉴权接口读取。学习日固定为 `Asia/Shanghai`，时间戳使用 UTC。

## 快速启动

需要已启动的 Docker Engine / Docker Desktop（Linux 容器）与 Docker Compose v2。以下 PowerShell 命令在仓库根目录执行；macOS / Linux 的对应操作见[本地开发手册](docs/runbooks/local-development.md)。首次构建需要下载依赖与镜像。

### 1. 准备配置

```powershell
Copy-Item deploy/.env.example deploy/.env
```

编辑 `deploy/.env`，填写非空 `POSTGRES_PASSWORD`、`ACCOUNT_USERNAME` 和 BCrypt `ACCOUNT_PASSWORD_HASH`。没有默认登录密码，配置文件已忽略提交。邀请码注册默认关闭。

仅用 Docker 生成密码 hash 时，先将 `ACCOUNT_PASSWORD_HASH` 设为任意非空占位值以通过构建配置校验，再执行：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml build backend
docker run --rm -it --entrypoint java interview-flashcards-backend '-Dloader.main=com.interviewflashcards.tools.PasswordHash' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

在交互终端输入两次密码（至少 12 个字符、最多 72 个 UTF-8 字节）。将输出的完整 BCrypt hash 替换到 `deploy/.env`，用单引号包裹，例如 `ACCOUNT_PASSWORD_HASH='$2a$12$…'`，避免 `$` 被 Compose 插值。也可按开发手册使用本机 Java 工具生成。

### 2. 启动并访问

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml up --build -d --wait
docker compose --env-file deploy/.env -f deploy/compose.yml ps
```

- 应用：<http://127.0.0.1:8088/>
- 健康检查：<http://127.0.0.1:8088/api/health>

使用配置账号登录，在“我的资料”上传 PDF、检查并确认章节，再到“学习设置”选择范围，返回首页开始学习。管理员由维护工具受控创建或绑定，详见[账号维护手册](docs/runbooks/accounts.md)。

### 3. 停止与保留数据

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml down
```

数据库、来源文件和临时文件分别使用独立命名卷；`down` 保留数据，需要保留数据时不要加 `-v`。公网部署使用独立的 `deploy/compose.production.yml` 与 HTTPS 配置，步骤见[发布、维护与回退手册](docs/runbooks/release.md)。

## 源码开发与验证

源码开发环境：Java 21、Maven 3.9.15、Node 24.13.0、npm 11、PostgreSQL 18。数据库、账号及存储环境变量配置见[本地开发手册](docs/runbooks/local-development.md)和 [backend/.env.example](backend/.env.example)；Spring 不自动读取 `.env`。

完成数据库和环境配置后，分别在两个终端运行：

```powershell
# 后端终端
cd backend
mvn -B -ntp spring-boot:run
```

```powershell
# 前端终端
cd frontend
npm.cmd ci --no-audit --no-fund
npm.cmd run dev
```

访问 <http://127.0.0.1:5173/>，Vite 将 `/api/` 代理至本机 `8080`。macOS / Linux 使用 `npm` 替代 `npm.cmd`。

| 检查 | 执行目录 | 命令 |
| --- | --- | --- |
| 前端测试 | `frontend/`，先执行 `npm.cmd ci` | `npm.cmd test` |
| 前端类型检查与生产构建 | `frontend/` | `npm.cmd run build` |
| 后端单元与契约测试 | `backend/` | `mvn -B -ntp test` |
| 后端集成测试与构建 | `backend/`，先配置专用测试库的 `DATABASE_*` 环境变量 | `mvn -B -ntp -Pintegration verify` |
| 备份工具回归 | 仓库根目录，Linux / WSL | `python3 -m unittest discover -s tools -p 'test_*.py' -v` |
| PDF 来源与边界回归 | 仓库根目录，Java 21、Maven 3.9+、Python 3.10+ | `./tools/pdf-probe/run.ps1 -RenderSamples` |

集成测试使用真实 PostgreSQL，在随机独立 schema 中运行并清理；请使用有创建 / 删除 schema 权限的专用测试数据库。测试需从完整仓库检出目录运行，后端 PDF 测试会读取 `docs/` 中的真实样本。

[GitHub Actions](.github/workflows/ci.yml) 配置了前后端检查、备份工具回归、完整 Compose 构建、容器重建持久化检查及隔离恢复验证。PDF 工具生成 `output/pdf-probe/report.md`，来源基线、输出与限制见 [PDF 验证工具](tools/pdf-probe/README.md)。

## 文档导航

- [产品设计](docs/设计文档.md) · [开发任务与完成记录](docs/开发文档.md) · [技术与数据规则决策](docs/decisions/) · [分阶段验收证据](docs/fixtures/)
- [本地启动、配置与排查](docs/runbooks/local-development.md) · [账号、邀请码与权限维护](docs/runbooks/accounts.md)
- [HTTPS 发布、维护与回退](docs/runbooks/release.md) · [当前 Debian 部署操作](docs/runbooks/debian-home-deployment.md) · [自动备份与恢复演练](docs/runbooks/automatic-backups.md)

| API 范围 | 文档 |
| --- | --- |
| 基础与账号 | [响应与错误码](docs/api/baseline.md)、[登录与文件](docs/api/auth-and-files.md)、[注册、密码与容量](docs/api/registration-and-accounts.md)、[个人邀请码](docs/api/personal-invitations.md) |
| 导入与阅读 | [解析任务与草稿](docs/api/imports-and-drafts.md)、[逐章检查](docs/api/chapter-review.md)、[题库与来源阅读](docs/api/card-library.md)、[多题库管理](docs/api/deck-management.md) |
| 学习与统计 | [评分与调度](docs/api/learning.md)、[每日学习](docs/api/daily-learning.md)、[个人复习参数](docs/api/review-parameters.md)、[学习趋势](docs/api/learning-trends.md) |
| 写入与同步 | [幂等写入协议](docs/api/mutations-and-sync.md)、[跨设备与评分冲突](docs/api/cross-device-sync.md)、[变更通知与增量同步](docs/api/incremental-sync.md) |
| 数据管理 | [完整备份与恢复](docs/api/backups-and-restores.md)、[题库删除与进度重置](docs/api/deck-data.md) |
| 分享与小组 | [题库副本分享](docs/api/deck-sharing.md)、[学习小组](docs/api/study-groups.md) |
| 平台管理 | [管理员与用户管理](docs/api/admin-accounts.md)、[平台知识库](docs/api/platform-library.md)、[Dashboard](docs/api/admin-dashboard.md) |

## 当前边界

- PDF 解析围绕仓库中的真实样本及其目录、标题布局验证；扫描件 OCR 和更多复杂排版仍属后续扩展。完整来源始终保留，异常内容需要人工检查。
- 待同步队列用于保留和重试修改，当前不承诺断网完成整套学习流程；清理站点数据前应先处理待同步内容。
- 题库分享与平台领取生成独立内容副本，各自编辑和复习；小组学习摘要默认关闭，可随时撤回。
- 个人 ZIP 覆盖个人学习空间；整站账号、权限、平台状态和部署回退使用服务器一致快照。自动备份的安装、保留策略、停机窗口与异地保存要求见运维手册。

更多 PDF 格式与 OCR、AI 辅助整理、口述模拟面试、部分恢复与备份合并等后续方向见[开发文档 P2](docs/开发文档.md#10-p2后续扩展)。
