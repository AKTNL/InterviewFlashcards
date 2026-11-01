# P0-13 公网部署与服务器恢复记录

日期：2026-11-01（Asia/Shanghai）。公网部署、证书续期演练、真实双端登录/同步及服务器快照隔离恢复已完成。本次部署收尾时暂缓的应用内 ZIP 恢复及实体手机旧队列重连，已在同日后续完成，见[公网恢复与重连记录](public-restore-2026-11-01.md)，P0-13/M5 已签收。下文保留部署阶段的实际证据和当时边界。

## 当前入口与环境

- 正式入口：`https://study.aktnl.dpdns.org:18443`。
- HTTP 检查入口：`http://study.aktnl.dpdns.org:18080`，308 跳转保留路径和查询参数。
- Debian GNU/Linux 13，Linux `6.12.107+deb13-amd64`，x86_64；Docker Engine 29.9.2，Compose v2.39.1-desktop.1。
- 部署目录 `/srv/interview-flashcards`，固定 Compose 项目名 `interview-flashcards`。
- 路由器外部/内部 `18080`、`18443` 转发到 Debian；宿主机 Nginx 按域名处理，不占用已有博客的站点配置。
- 宿主机 HTTPS Nginx 校验并代理到 `https://127.0.0.1:18444`；容器另有 `127.0.0.1:18081` HTTP 入口，后端与数据库未发布主机端口。
- 设备按实际操作结果验收。用户明确选择不采集手机型号、系统及浏览器版本；本记录不将型号信息作为门槛。

## 发布产物

发布 ID：`p013-20261101-01`。Git 基础提交为 `9c37c87`，运行代码还包含当时未提交的 P0-13 工作区修改，以源码包清单及哈希固定版本。

| 产物 | SHA-256 |
| --- | --- |
| 源码包 `interview-flashcards-p013-20261101-01-source.tar.gz`（225 个源码文件及清单） | `7096f390c71a700dfaa1b72a65f75f51f438fd36e49725ed29139252f586a4e7` |
| 镜像包 `interview-flashcards-p013-20261101-01-images.tar.gz`（324,528,598 字节） | `9d299493dffa60c799a6423c2815c70fc365be13fbeb9f9585d5e9b6a4dfc8dc` |

三个镜像均为 `linux/amd64`，下列为服务器 `docker image inspect .Id` 的配置 ID：

| 镜像 | 配置 ID |
| --- | --- |
| `interview-flashcards-backend:p013-20261101-01` | `sha256:b5908588873499156e3a38ccde2321657a760a415b2472c29fc3315482b359f1` |
| `interview-flashcards-web:p013-20261101-01` | `sha256:c7f28a7d7c5467a141eb147a9c2a74216d523f0b4c010fa777f8300d35d1b6ed` |
| `postgres:18.3-alpine` | `sha256:f6d4985d97e69bb9de4d5e24f9f24cbfaa90d30877ce935a8ee872f62efbb621` |

Debian 和 Windows 当时均无法正常解析/连接 Docker Hub。Windows 使用本地缓存依赖重新编译 Java 21 后端、完成前端类型检查和生产构建；以已缓存的 P0-13 运行环境离线组装新镜像并导出。后端镜像内 JAR 哈希与新构建产物一致，Nginx 和 Java 21 基本检查通过。Debian 校验包哈希后导入，用 `--no-build --pull never` 启动。原 P0-13 全量自动化证据仍见原验收记录，本轮没有重跑全部前后端用例。

## 已完成检查

| 检查 | 实际证据 |
| --- | --- |
| 公网可达、TLS、308、HSTS/MIME/缓存、SPA/404、数据库、鉴权和请求 ID | Windows 连接手机热点后运行 `release-check.py`，用户提供 `result=PASS`、`tlsVerified=true` 的输出 |
| 实体手机登录 | 用户通过移动网络确认 HTTPS 页面和配置账号可用 |
| 家中 Windows 登录 | 添加本机 hosts 内网解析后，用户确认可用 |
| 导入、单章确认和学习 | 用户操作；服务器快照核对 1 份资料、13 章、158 草稿、17 正式卡片、1 条评分事件 |
| 双端同步 | 用户确认手机和电脑同步 |
| ZIP 导出/下载 | 用户确认双端可下载；快照中有 1 条备份任务 |
| 容器重建后的数据保留 | 用户执行 `up --force-recreate --no-build --pull never --wait`，确认数据检查可用 |
| 开机自启动 | `docker`、`nginx`、`certbot.timer` 均为 `enabled` |
| 证书续期及部署钩子 | Certbot `--dry-run --run-deploy-hooks` 成功，宿主机及容器 Nginx 检查/重载成功 |
| 一致快照 | 新脚本停写后生成数据库和两个文件卷快照，14 个文件全部通过 SHA-256 校验，随后三个服务恢复健康 |
| 隔离恢复 | 授权 SSH 后直接执行：`ISOLATION_CHECK`、`DATABASE_MATCH`、`FILES_MATCH`、`RESTORED_HEALTH`、`RESTORE_CHECK` 全部 PASS |
| 生产状态复核 | 隔离恢复后三个生产服务 healthy；正式入口经本机解析验证 TLS 及 `/api/health`，数据库 UP |
| 演练资源清理 | `interview-flashcards-recovery-check` 的容器、三个新卷和网络全部移除；原生产卷保留 |
| 服务器之外的副本 | 一致快照下载到 Windows 当前用户专用 ACL 目录，重新校验 14 个文件全部一致 |

隔离恢复使用同一 PostgreSQL 18.3 和同次快照的镜像/配置，在启动应用前逐表比较所有行的确定性 JSON 聚合指纹。实际匹配 29 张 public 表；文件卷中的 2 个文件逐项匹配，暂存卷文件数为 0。恢复应用启动后，通过具有系统信任证书校验的 HTTPS 请求读取数据库健康状态。此演练验证同版本快照恢复；尚无第二个已发布版本，因此没有宣称执行跨版本数据库降级或真实生产入口回切。

## 证书与网络维护

- Certbot 4.0.0 + `dns-cloudflare`，Cloudflare Token 限指定区域的 DNS 编辑及区域读取；凭据仅保存在服务器 `/etc/letsencrypt/cloudflare-study.ini`（600）。
- 正式证书到期日为 2027-01-30；`certbot.timer` 已启用，自动续期演练通过。
- 部署钩子 `/etc/letsencrypt/renewal-hooks/deploy/50-interview-flashcards` 同步证书到 `/srv/interview-flashcards/tls`，校验并重载容器及宿主机 Nginx。
- 证书目录 700，私钥 600。该目录为独立复制目录，避免将 Let’s Encrypt 的跨目录符号链接直接挂入容器。
- 日常入口使用 HTTPS `18443`。浏览器 HSTS/HTTPS 自动升级可能将 HTTP `18080` 升级成 HTTPS `18080` 并报协议错误；完整公网跳转检查由无 HSTS 缓存的 Python 客户端验证。
- 家中路由器不支持本次公网地址回环访问；Windows hosts 将该域名指向 `192.168.1.7`。电脑离开家中网络时需停用此行，或另行采用可区分内外网的 DNS 方案。
- `192.168.1.7` 当前为 DHCP 动态地址。用户没有路由器 DHCP 管理权限，地址保留未设置；地址变化时需更新端口映射和 Windows hosts。公网 IPv4 固定性和 DDNS 未经验证，公网地址变化时同样需要更新 DNS。

## 快照与工具

- 服务器快照：`/srv/interview-flashcards/output/server-snapshot-20261101-01`。
- 隔离恢复报告：`/srv/interview-flashcards/output/recovery-check-VuaFtLwQ`。
- Windows 受限副本：`D:\InterviewFlashcards\output\p013-server-backup-20261101\server-snapshot-20261101-01`。
- 新工具：[一致快照脚本](../../../tools/server-snapshot.sh)、[恢复检查脚本](../../../tools/check-server-snapshot.sh)、[隔离配置检查](../../../tools/recovery-check-config.py)、[表内容指纹 SQL](../../../tools/snapshot-fingerprints.sql)。
- 工具验证：5 项快照流程模拟测试、9 项隔离配置测试；实际临时 PostgreSQL 验证空表、行顺序无关性、内容变动可检测及 dump/restore 指纹一致；缓存后端镜像验证文件解压、空暂存卷及逐文件哈希。

首次交互式 heredoc 快照因 Docker 默认读取 stdin，只生成了容器内临时转储，未记为成功。修正版工具采用独立脚本、关闭 Docker stdin，并在中断/失败时尝试恢复生产服务；成功标记仅在快照校验和服务恢复都通过后打印。服务器及 Windows 副本均来自修正版的完整一致快照。

这些快照含账号散列、数据库及个人资料，受限保存，不提交到 Git。快照不包含 Cloudflare Token 或证书私钥；重建宿主机时可使用域名管理权限重新配置 DNS 验证并签发证书。

## 部署收尾时暂缓的功能验收（后续已完成）

用户在本次部署阶段明确只完成公网部署，暂缓应用内 ZIP 恢复和恢复后旧设备离线队列的功能验收。当时线上 `restore_job` 数量为 0，此两项未计为公网验收通过。随后用户要求继续 P0-13，并明确确认恢复；两项已实际完成，证据见[公网恢复与重连记录](public-restore-2026-11-01.md)。服务器级隔离恢复与应用内恢复分别记录，不相互替代。
