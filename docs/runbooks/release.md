# HTTPS 发布、维护与回退（P0-13）

2026-11-01 更新：P0-13/M5 已签收。公网 HTTPS、实体手机登录/双端同步、自动续期演练和服务器快照隔离恢复见[公网部署记录](../fixtures/p0-13/public-deployment-2026-11-01.md)与[当前 Debian 日常操作](debian-home-deployment.md)；应用内 ZIP 恢复、实体手机旧队列重连及服务端数据未被覆盖的核对见[公网恢复记录](../fixtures/p0-13/public-restore-2026-11-01.md)。[P0-13](../fixtures/p0-13/README.md) 保留完整本地验收历史。以下步骤用于今后的发布和维护。

## 部署配置

生产使用独立的 `deploy/compose.production.yml`，**不与本地 HTTP 的 compose.yml 合并**。只开放 Web 的 80/443，数据库与后端无主机端口。Nginx 终止 TLS，同源代理 `/api`，覆盖客户端的转发头；内部后端启用原生转发头解析和强制 Secure/HttpOnly/SameSite=Lax Cookie。禁止直接暴露内部后端。数据库、不可变来源/图片/备份文件、上传暂存分别使用三个命名卷。

以下服务器命令使用 Bash，在仓库根目录执行。固定项目名 `interview-flashcards`，每次都使用同一份配置与项目名；改变项目名会指向另一组空卷。部署目录需持续保留代码中的代理配置文件，不能只复制镜像。

```bash
cp deploy/.env.production.example deploy/.env.production
# 填写数据库密码、账号、BCrypt hash、域名、证书绝对目录和本次不可变镜像 tag。
# 生成 hash 的交互工具及密码轮换说明见 local-development.md。
chmod 600 deploy/.env.production
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml config --quiet
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml build
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml up -d --wait --wait-timeout 240
python3 tools/release-check.py --base-url https://study.example.com --http-url http://study.example.com
```

把示例域名换成自己的域名，DNS 指向服务器；`TLS_CERT_DIR` 中必须存在域名匹配的 `fullchain.pem` 和 `privkey.pem`（包含完整证书链，私钥仅管理员可读）。证书管理在宿主机完成，申请/续期工具可通过 DNS 验证；当前配置没有 HTTP ACME challenge 路由。不要提交证书私钥、`.env.production`、运行时文件或备份。公网检查使用系统信任链，不能以跳过证书检查代替验收。默认 HTTPS 端口 443，HTTP 永久跳转保留路径/查询参数；页面启用一年 HSTS，不影响其他子域。

证书续期后验证并 reload；挂载整个证书目录以支持宿主机原子替换。若使用符号链接，目标文件也必须在挂载目录内。首次启动证书无效会使 Web 健康检查失败。

```bash
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec -T web nginx -t
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec -T web nginx -s reload
```

发布版本记录：Git 提交、后端/Web 镜像 tag 和 image ID/digest、PostgreSQL 版本、Flyway 版本、配置副本和证书到期时间。不要覆盖历史镜像 tag；本地 `p013-local` 仅用于演练。需要多机传输时推送到自己的镜像仓库并按 digest 部署。保存上一版代理/Compose 文件，因为它们通过宿主机挂载，不包含在镜像中。

## 健康检查与故障定位

`/api/health` 实际读取 Flyway 创建的数据库记录，并提供服务端日期与时区。容器按 db → backend → web 等待健康；Web 内部探针只验证进程/反向代理/数据库，独立的 `release-check.py` 才验证外部 TLS 信任及主机名。`ps` 不健康时不能开放发布入口。

```bash
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml ps
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml logs --no-color --tail=200 backend
```

HTTP 响应 `X-Request-ID` 与 JSON `meta.requestId` 对应日志编号；导入记录 job UUID，备份/恢复记录任务 UUID 和错误类型。不要启用请求正文/SQL 绑定值调试日志，不向问题单粘贴密码、Cookie、正文或完整备份。三个服务均限制 Docker 日志为 3×10 MiB。会话在 JVM 内存中，后端重启后所有设备重新登录，学习记录保留。

数据库不可用先修复连接/磁盘，不能删卷重新初始化。Flyway 校验失败检查镜像/已执行迁移，不能修改历史迁移或执行 clean。任务中断可到页面按任务错误显式重试；未提交恢复会回到重新预览，已提交 READY 不重复替换。

## 数据保存、备份与暂存清理

`database` 保存账号及所有结构化数据；`files` 保存原 PDF、图片、完整 ZIP 和不可变资源；`temporary-files` 保存 multipart/流式上传及解压临时目录。备份 ZIP 不能替代账号配置与部署密钥的保存。用户主动导出/校验/确认恢复步骤见 [本地手册的备份恢复章节](local-development.md#p0-11-备份与恢复操作)。下载 ZIP 到服务器外保存并实际演练恢复，不能仅看到 READY 就宣称可用。

上传/下载 ZIP 副本保留 7 天，小时清理；成功恢复的结果保持 READY。启动清理遗留备份解压目录，以及上传 `upload-数字.part` 与文件发布 `UUID.part`。后两类仅在单 JVM 存储初始化、接受上传前清理，不周期删除在途文件。清理不递归、不跟随符号链接、不移除已提交 UUID 文件。业务 PDF、已生成图片、旧恢复命名空间和失败后未引用的持久化资源暂不自动回收，需监控卷空间；不可按文件年龄删除 `files`。计划中的自动备份/资源回收属于 P1。

`docker compose down` 保留卷，日常停机不使用 `down -v`。PostgreSQL 18 的卷挂载 `/var/lib/postgresql`；主版本升级需独立迁移演练，不能直接复用数据目录启动其他主版本。

## 升级前一致性快照

当前 Debian 环境可使用 `bash tools/server-snapshot.sh 新快照标签` 自动停写、捕获数据/文件和指纹、校验并恢复服务，再以 `bash tools/check-server-snapshot.sh 同一标签` 在独立项目验证恢复。用法和限制见 [当前部署手册](debian-home-deployment.md#备份及恢复演练)。脚本中的 Docker 命令关闭 stdin，避免以 heredoc 执行时读走后续命令；以下逐条执行的通用步骤仍可用于其他部署环境。

先处理所有设备待同步内容并导出用户完整 ZIP。再通知停机、阻止新流量，停止 Web 和后端，等待优雅退出，保持数据库运行。这样应用写入与文件发布全部停止；从同一个停机窗口保存数据库和两个文件卷。以下是显式维护操作，执行前确认环境和磁盘空间。

```bash
mkdir -p output/pre-upgrade
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml stop web backend
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/pre-upgrade.dump'
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml cp db:/tmp/pre-upgrade.dump output/pre-upgrade/database.dump
docker compose -p interview-flashcards --env-file deploy/.env.production -f deploy/compose.production.yml run --rm --no-deps --user 0 --entrypoint sh -v "$PWD/output/pre-upgrade:/backup" backend -c 'tar czf /backup/files.tgz -C /data/files . && tar czf /backup/temporary-files.tgz -C /data/tmp .'
sha256sum output/pre-upgrade/database.dump output/pre-upgrade/files.tgz output/pre-upgrade/temporary-files.tgz > output/pre-upgrade/checksums.txt
```

保存当前镜像 ID/配置/提交与这些快照到独立介质，目录中包含私密资料及账号散列，按敏感备份限制访问。验证 `pg_restore --list`、tar 清单及校验和；Windows 不要用旧 PowerShell 把二进制 pg_dump 直接重定向，使用上面的容器文件与 `compose cp`。不要把旧快照回写到仍运行的应用。

更新到新版本代码和不可变镜像引用后，执行 `up -d --wait`，Flyway 自动升级，检查日志和迁移历史。通过 TLS 检查、登录、已有草稿、完整答案/PDF、一次评分/撤销、双端刷新、导出及下载后再结束维护。升级不得重算既有 dueDate。

## 回退

后端默认不执行数据库降级迁移。若新版未执行不兼容迁移且未产生不兼容写入，可恢复上一版镜像和代理配置；先验证其能读取当前 schema。不能仅改旧 tag 就假定数据兼容。

若需要数据库回退，在隔离 Compose 项目和新卷内启动**相同 PostgreSQL 主版本**，先不启动应用；从维护快照恢复数据库和两个文件卷，再以同一快照对应的旧镜像/配置启动。禁止把旧数据库快照与新文件卷混用。空目标库恢复示例（将新项目的环境文件指向新卷和不冲突的端口）：

```bash
# 使用固定的新项目名；不合并或删除原来的生产卷。
docker compose -p interview-flashcards-rollback --env-file deploy/.env.rollback -f deploy/compose.production.yml up -d --wait db
docker compose -p interview-flashcards-rollback --env-file deploy/.env.rollback -f deploy/compose.production.yml cp output/pre-upgrade/database.dump db:/tmp/database.dump
docker compose -p interview-flashcards-rollback --env-file deploy/.env.rollback -f deploy/compose.production.yml exec -T db sh -c 'pg_restore --exit-on-error --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB" /tmp/database.dump'
docker compose -p interview-flashcards-rollback --env-file deploy/.env.rollback -f deploy/compose.production.yml run --rm --no-deps --user 0 --entrypoint sh -v "$PWD/output/pre-upgrade:/backup:ro" backend -c 'tar xzf /backup/files.tgz -C /data/files && tar xzf /backup/temporary-files.tgz -C /data/tmp && chown -R app:app /data/files /data/tmp'
docker compose -p interview-flashcards-rollback --env-file deploy/.env.rollback -f deploy/compose.production.yml up -d --wait
```

原生产栈继续停机；验证新卷中的数据、PDF 和学习记录，再切换入口。原卷保留用于排查，不能对非空目标库直接反复 `pg_restore`。完整数据库回退会撤销快照后的学习，必须说明恢复时间点。由于客户端旧队列可能携带被回退的版本，在隔离旧入口后，对恢复库每个用户切换代次并递增修订，再开放写入；旧队列会转为待核对：

```sql
BEGIN;
UPDATE app_user SET data_epoch=gen_random_uuid(), revision=revision+1;
UPDATE sync_conflict c SET data_epoch=u.data_epoch, version=c.version+1
FROM app_user u WHERE c.user_id=u.id AND NOT c.resolved;
COMMIT;
```

该步骤仅用于已明确选择的整库回退，同时保留未解决候选在新代次中继续核对的能力；旧收据不迁移，旧请求仍被拒绝。用户应用内 ZIP 恢复已经自动切换代次，无需手动执行。实体手机与公网证书/域名验收通过前，本地演练不等于上线完成。

## 第一版限制与发布门槛

仅适配当前 222 页 PDF，图表/异常字形仍需来源核对；预置单用户入口、单后端 JVM；在线应用，本地队列仅保护短时断网提交；复习间隔 `[1,3,7,14,30]` 是 `rules-v1` 候选参数，不自动判断答案正确性。无公开注册、任意 PDF/OCR、完整离线学习、自动备份和自动资源回收。

未解决的数据丢失、重复计数、来源缺失、恢复覆盖均阻止发布。发布前还需在用户域名上验证系统信任证书链、外网端口和续期方案，并在实体手机与电脑同一账号完成章节续检、完整阅读/来源、自评同步、导出恢复、旧设备离线重连。记录设备/系统/浏览器、时间、结果及任务编号，不用手机视口仿真代替实体手机签收。
