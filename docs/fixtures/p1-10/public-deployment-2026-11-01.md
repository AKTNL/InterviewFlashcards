# P1-08～P1-10 公网升级记录

日期：2026-11-01（Asia/Shanghai）。本地提交 `e3efdc208b3363195eed1819392df8ad7c803586` 已发布到现有 Debian 服务，发布 ID 为 `p110-20261101-01`。

正式入口：<https://study.aktnl.dpdns.org:18443>。Compose 项目保持 `interview-flashcards`，部署目录保持 `/srv/interview-flashcards`，沿用现有数据卷、证书和宿主机代理。PostgreSQL 保持 `18.3-alpine`。前后端已升级，Flyway V11、V12、V13 均成功执行。

## 发布产物

沿用上次缓存的 Java 21 / Nginx 运行环境，在 Windows 重新执行后端 Maven package 和前端生产构建，再组装 `linux/amd64` 镜像、上传并校验 SHA-256。服务器不拉取 Docker Hub 镜像。

| 项目 | 固定版本 / 校验值 |
| --- | --- |
| 源码包 SHA-256 | `b08302a44fbd0e2f1069f751c134c8526d2dfde30df161ecc9855825074755cd` |
| 后端 JAR SHA-256 | `9752d5b291db8ca7ebaa6f284871dd30ac56820df8c43e4d9c34356e7845cbf6` |
| 后端镜像 | `interview-flashcards-backend:p110-20261101-01` |
| 后端服务器配置 ID | `sha256:4ec520072b640a13e32fab2ce33749b2e5a9ba84d0738dc7a4f4dda5c92dd133` |
| Web 镜像 | `interview-flashcards-web:p110-20261101-01` |
| Web 服务器配置 ID | `sha256:e2e09f106543caee7f581f20d658377c1e897135813ad31ea1650884b7fdc0f9` |

本机产物位于 `output/release/p110-20261101-01`；服务器产物与证据位于 `/srv/interview-flashcards/output/releases/p110-20261101-01`，包含源码包、镜像包、校验清单、发布清单、隔离验证和切换脚本。服务器根部的 `release-manifest.json` 已更新。旧版镜像和升级前配置均保留。

## 验证结果

- 本次前端测试 129 项通过，类型检查与生产构建通过；后端离线 package 成功。本次未重新运行全部后端测试，原同提交验收的 24 项单元测试与 93 项 PostgreSQL 集成测试证据见 [P1-10 验收](README.md)。
- 使用 `pre-p110-20261101-01` 完整快照恢复独立项目 `interview-flashcards-recovery-check`，确认数据库、文件卷、网络独立，Web 仅绑定 `127.0.0.1:18082/18445`。恢复后的全表指纹与快照一致。
- 隔离环境先固定旧业务表的列集合，升级到 V13 后比较原有列的逐表行数与确定性内容指纹；原有业务数据及账号密码散列一致。新增列和迁移历史单独核对。8 个已有文件 SHA-256 一致，Nginx、可信 TLS、应用与数据库健康检查通过。成功后清理本次隔离容器、卷和网络。
- 正式切换前再次生成 `pre-p110-final-20261101-01`，停止 Web/后端，并确认停止后的全表指纹仍与快照一致，才执行升级。V13 启动后、开放 Web 前再次核对 28 张原有业务表的数据和密码散列，全部一致；8 个文件校验通过。
- 正式三个服务均 healthy。服务器保留真实域名及系统证书验证、仅将连接解析到本机运行 `release-check.py`；Windows 客户端也运行同一检查，均 `PASS`。覆盖 HTTP 308、TLS、HSTS/MIME、页面缓存、SPA/静态 404、数据库健康、请求 ID 及匿名访问拒绝。
- 正式首页、入口脚本及注册、题库分享、学习小组页面脚本 SHA-256 与本次本机构建一致；新注册状态接口正常，`/api/groups`、`/api/shares` 和 `/api/storage/usage` 的匿名请求均拒绝。
- 两份服务器快照均已下载到本机 `output/release/p110-20261101-01/server-backups` 的当前 Windows 用户专用目录，各 14 个文件逐项通过 SHA-256 校验。快照含数据库、文件及私密环境配置，不提交 Git。
- 16:11（Asia/Shanghai）完成最终服务状态核对，确认旧账号绑定正确、隔离资源清理完成。临时 SSH 公钥已撤销，再次使用该密钥登录被拒绝。最终报告保存在本机及服务器发布目录的 `completion-report.json`。

## 使用与维护

刷新页面并重新登录，继续使用原账号和密码。新版包含独立账号与密码管理、题库分享、学习小组功能。邀请码注册保持关闭；如需邀请新账号，按 [账号维护手册](../../runbooks/accounts.md) 生成邀请码并启用注册。

本次完成服务器升级和自动化检查；没有代替用户执行实体手机登录、两个真实账号之间的分享或小组操作，也没有进行新的手机外网验收。实体设备上的新功能操作待用户复验。

跨版本回退须停写并整体恢复升级前数据库和文件快照，不能只切回 V10 旧镜像，见 [发布手册](../../runbooks/release.md)。
