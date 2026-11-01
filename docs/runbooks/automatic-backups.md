# P1-07 服务端自动备份与恢复演练

面向现有 Debian 单机 Docker Compose 部署。自动备份复用停写一致快照：停止 Web/后端，保持 PostgreSQL 运行，同一窗口捕获整库、files 和 temporary-files，保存部署配置及运行镜像 ID，再恢复服务。每次会短暂停机并使现有登录会话失效，设备未同步队列不在服务器备份内。每日 04:00、周日 05:00 均为 **Asia/Shanghai**，带最多 5 分钟随机延迟；机器关机错过的运行由 Persistent 补执行。

## 默认策略

| 项目 | 默认值与行为 |
| --- | --- |
| 完整快照 | 每日一份；首次同时执行真实隔离恢复 |
| 保留数量 | 最多 7 份完整自动快照，无按年龄删除 |
| 保留空间 | 快照目录总文件长度最多 20 GiB，包含失败残留 |
| 可用空间 | 至少保留 2 GiB；开始前另预留 max(1 GiB，上次压缩快照 × 1.2) |
| 恢复演练 | 每周恢复最新完整快照，比较全部表指纹、全部文件集合/校验和及实际 HTTPS/数据库健康 |
| 不得删除 | 最新完整快照、最近一次实际恢复成功的快照、失败/损坏/陌生目录、符号链接 |
| 故障状态 | 进程非零退出，systemd failed，受限 status.json 保存阶段、时间及日志；旧成功记录保留 |
| 过期检查 | 备份超过 36 小时或成功恢复超过 192 小时，status 非零退出 |
| 日志 | 最近 20 次工具日志，目录 700、状态/日志 600；journal 由系统日志策略维护 |

仅在新备份完成、校验通过且已有实际恢复成功的副本后，才按时间从旧到新清理可删除副本，直到数量和空间同时达标。预算小于受保护副本总量时报告失败，不牺牲恢复点；磁盘不足时在停机前失败，不先删旧备份腾空间。失败或损坏目录计入空间但必须人工处理。空间阈值是容量预检，不能保证压缩率或运行过程中其他程序用量；还需监控 Docker 数据目录所在文件系统，为解压后的文件与数据库预留空间。默认快照目录与 Docker 可能位于不同分区。

## 安装与首次验收

以下操作在 Debian 服务器执行。仓库交付没有自动安装或启用线上定时器。执行首次备份及启用 Persistent timer 前选择可接受的停机窗口，先保存设备未同步内容。

```bash
cd /srv/interview-flashcards
cp deploy/backup-policy.example.json deploy/backup-policy.json
# 核对 app_dir、backup_dir、keep_count、max_bytes 和磁盘空间，按服务器容量调整。
chmod 600 deploy/backup-policy.json
sudo install -d -m 700 -o kevin -g kevin /srv/interview-flashcards-backups
python3 tools/automatic-backup.py backup --config deploy/backup-policy.json
python3 tools/automatic-backup.py status --config deploy/backup-policy.json
```

依赖 Python 3.10+、Bash、util-linux flock、GNU tar/coreutils、curl、Docker Compose v2 和已有健康部署。维护用户需拥有 Docker 权限、可读生产环境/宿主机 Nginx 配置、可写 output 和备份目录。新部署需将 unit 的 `User=kevin`、工作目录与配置路径改成实际值。默认宿主机 Nginx 路径为 `/etc/nginx/sites-available/interview-flashcards`，不同路径可通过 unit 的 `Environment=FC_HOST_NGINX_CONFIG=绝对路径` 指定。默认生产项目名仍是 `interview-flashcards`；其他部署可设置 `FC_COMPOSE_PROJECT`，所有手动命令须使用相同项目。

首次成功后启用两个 timer：

```bash
sudo install -m 644 deploy/systemd/interview-flashcards-* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now interview-flashcards-backup.timer interview-flashcards-restore-check.timer
systemctl list-timers 'interview-flashcards-*'
```

需要自行安排外部监控调用 status；本工具不发送邮件或聊天通知。timer 的触发不等于任务成功，`systemctl --failed` 和 status 都应纳入日常检查。

```bash
python3 tools/automatic-backup.py status --config deploy/backup-policy.json
systemctl status interview-flashcards-backup.service interview-flashcards-restore-check.service --no-pager
journalctl -u interview-flashcards-backup.service -u interview-flashcards-restore-check.service --since yesterday
# 立即演练最新自动快照（不触碰正式数据卷）
python3 tools/automatic-backup.py verify --config deploy/backup-policy.json
```

backup/verify/status 共用调度锁；手动快照、手动演练与调度调用的脚本另共用 `output/server-maintenance.lock`。遇到重叠运行立即非零返回，不覆盖正在运行的状态。升级/手工维护前停用两个 timer 并等当前 service 结束，避免其他运维命令绕开锁。

## 快照、版本与异地保存

自动快照位于 `backup_dir/snapshots/auto-UTC时间-随机标识`，SHA256SUMS 覆盖数据库、两个压缩卷、表/文件指纹、`.env.production`、Compose/Nginx 配置和运行镜像的 `compose.images.json`。只有数据校验和**服务重启成功**才出现 SNAPSHOT_COMPLETE。调度器再次逐个文件计算 SHA-256；清理前也重新验证。数据库涵盖所有用户、平台题库、权限、邀请码、审计、学习数据及 Flyway，不使用个人 ZIP 替代整站备份。

实际镜像 ID 固定到快照，恢复时禁止构建或拉取；不要清理仍被保留快照引用的镜像。镜像内容、TLS 私钥不在快照中，需单独受控保存镜像包/可取回的 digest、证书续期凭据和部署密钥。当前有效证书位于 app_dir/tls，演练验证系统信任链；仅本地测试通过 `FC_RECOVERY_CA_FILE` 指定可信 CA，始终校验证书与域名。旧 P0-13 手动快照仍可用原检查脚本，新自动快照必须包含运行镜像固定文件。

备份包含所有人的私人资料、密码散列和部署密码，只允许维护用户读取；不要提交、公开挂载或上传到普通问题附件。需另将完整快照与镜像保存到服务器外受限介质，本机定时备份不能防止整机/磁盘损毁。保留策略只管理该专用目录中的完整 auto-* 副本，不清理业务 files 卷或用户 ZIP。

## 故障与恢复

- 空间不足：扩大容量、调整合理阈值，或在保存到异地并验证后人工处理失败目录；再次运行 backup。不要手工删最后的恢复成功副本。
- dump/tar/校验失败：保存日志和残留目录；脚本 EXIT trap 尝试恢复正式服务。重启失败时报告失败，先用原生产 Compose `ps` / `up -d --no-build --pull never --wait` 修复，再重试。断电、SIGKILL 或 Docker 守护进程失效无法依靠 trap 恢复，重启后须检查正式服务；running 状态会被 status 识别为需要检查。
- 恢复失败：旧成功恢复时间不更新，现场保留在 `interview-flashcards-recovery-check` 项目和 `app_dir/output/recovery-check-*`。修复缺失镜像、证书或容量后，先检查该项目，确认只属于演练后移除其容器/卷，再重试；脚本拒绝复用已有演练资源。成功报告不含环境密码，成功演练删除临时凭据及演练卷；报告目录需按运维保存周期归档。
- 人工恢复指定自动快照：设置 `FC_SNAPSHOT_ROOT=/srv/interview-flashcards-backups/snapshots` 后运行 `bash tools/check-server-snapshot.sh 标签`，仍只做隔离检查。真正切换生产按 [发布手册的回退步骤](release.md#回退)，明确停写、时间点和数据代次；本工具不会自动覆盖生产。

本地重现和范围见 [P1-07 验收记录](../fixtures/p1-07/README.md)。
