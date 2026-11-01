# P1-11 公网升级记录

日期：2026-11-01（Asia/Shanghai）。已将提交 `d2fc6d7aa26fde8c2b2764336e741e867fca635c` 发布到 <https://study.aktnl.dpdns.org:18443>，发布 ID 为 `p111-20261101-01`。新增个人中心邀请码与分步注册页面，前后端与 V14 同步升级。

沿用 `/srv/interview-flashcards`、Compose 项目 `interview-flashcards`、原生产数据卷、PostgreSQL `18.3-alpine`、端口和证书。注册保持关闭，未授予任何账号个人发码权限；全站注册上限使用新配置默认值 100。启用和授权操作见 [账号维护手册](../../runbooks/accounts.md)。

## 版本与产物

Windows 使用 Java 21 从源码执行离线 `clean package`，前端重新执行生产构建、类型检查及测试，136 项前端测试通过。后端全量测试没有在本次发布重复运行，同提交的 24 项单元测试和 103 项集成测试记录见 [P1-11 验收](README.md)。此前集成测试使用 PostgreSQL 18.6，本次隔离升级与生产实际使用 18.3。

复用缓存的 Java 21 / Nginx 基础环境组装 `linux/amd64` 镜像，服务器离线导入，启动使用 `--no-build --pull never`。发布包与 SHA-256 清单位于本机 `output/release/p111-20261101-01`，服务器对应 `/srv/interview-flashcards/output/releases/p111-20261101-01`，其中 `source.tar.gz` 固定本次提交，根部 `release-manifest.json` 已更新。

| 产物 | SHA-256 / 服务器配置 ID |
| --- | --- |
| 源码包 | `06b928cde383809d4cb9e91d57177ca128181be69e964a35490ecf611a751999` |
| JAR（服务器镜像内已核对） | `1e6f4648080c3c759630014b63b5d59de89cc58ec1319bb65e190fcbf2194cbe` |
| `interview-flashcards-backend:p111-20261101-01` | `sha256:3171fd8dd40dd4cc80ad0c10261210a00f6ef984b813e3424142fc3e900e7d92` |
| `interview-flashcards-web:p111-20261101-01` | `sha256:02fb52f06019ecb254066d67d4cedb3fa95d2e142b468b313172af149d9c451f` |

## 数据保留与检查

先生成一致快照 `pre-p111-20261101-01`，恢复到独立项目 `interview-flashcards-recovery-check`。核对独立卷和网络、仅回环绑定 `18082/18445`，恢复后的全表指纹与快照一致。升级到 V14 后，以升级前的列集合核对所有原业务表的行数和内容指纹，账号密码与原有数据一致。8 个文件逐项校验成功；V14 的注册守卫存在，旧邀请码来源为 `MAINTENANCE` 且邀请人为空，新增权限表为空。可信 TLS、Nginx、应用和数据库健康检查通过，随后移除此隔离项目的容器、卷及网络。

正式切换前再次生成快照 `pre-p111-final-20261101-01`。停止 Web/后端后核对全表指纹未发生变化，再升级后端；V14 成功执行，原业务数据和密码、旧邀请码默认字段、8 个文件核对通过后开放 Web。三个正式服务均 healthy。两份快照各 14 个文件均已下载到本机当前 Windows 用户专用的 `output/release/p111-20261101-01/server-backups` 目录并校验成功，旧镜像及配置保留。

服务器保留真实域名和系统证书信任、仅将连接解析到本机执行 `release-check.py`；Windows 客户端也执行同一检查，均 `PASS`。覆盖 TLS、308 跳转、HSTS/MIME、缓存、SPA/静态 404、数据库健康、请求 ID 与匿名访问拒绝。正式首页、入口、个人中心、登录、注册、分享及小组脚本哈希与本机构建一致，`/api/account/invites` 等私有接口拒绝匿名访问，注册状态仍为关闭。证据保存于本机 `verification.txt`、`server-evidence` 及服务器发布目录。

本次未创建生产测试账号、发出真实邀请或启用注册，未进行实体手机上的新注册流程验收。刷新并用原账号重新登录后可查看新版个人中心；需要开放邀请时另行授权并启用注册。

16:51（Asia/Shanghai）完成最终核对，40 张原有业务表的数据指纹保持一致，预置账号绑定正确，隔离资源已清理。临时 SSH 公钥已撤销，复测该密钥登录被拒绝；本机临时密钥也已删除。最终状态见本机及服务器发布目录的 `completion-report.json`。回退须停写，按 [发布手册](../../runbooks/release.md) 使用匹配版本的完整数据库、文件和配置快照，不直接将旧镜像连接到已升级数据库继续写入。
