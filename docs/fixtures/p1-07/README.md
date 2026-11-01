# P1-07：服务端自动备份、保留策略与恢复演练

状态：已完成仓库实现与本地验收（2026-11-01），线上定时器尚未安装或启用。

## 交付

- Python 标准库调度器：`backup` / `verify` / `status`，配置示例及两组 systemd service/timer。默认上海时间每日 04:00 备份、周日 05:00 实际恢复，支持错过补执行。
- 沿用 P0-13 停写整库与双文件卷快照；固定运行容器的镜像 ID，成功恢复正式服务后才写完成标记；完整清单逐文件重新校验。
- 默认最多 7 份/20 GiB，另有空间预检；新副本完成后才清理，保护最新与最近实际恢复成功副本。首次自动备份必须通过真实恢复；预算不足、损坏/失败残留明确报告，保留恢复点和现场。
- 手动/自动维护锁，原子状态、最后成功时间、空间/数量及清理结果、20 份受限日志；过期、失败、运行中断由非零状态检查识别。
- 每周恢复到独立项目/新卷，仅回环端口；比较所有表的数量/内容指纹、完整文件集合/内容及证书受信的 HTTPS 健康。成功清理临时凭据及演练卷，失败保留现场；不会切换生产入口。

## 自动验证

WSL Ubuntu / Python 3、Bash、Docker Desktop Linux 引擎执行。29 项全部通过：

| 范围 | 结果 |
| --- | --- |
| 数量与字节保留、最新/已恢复保护、不可达预算 | 通过 |
| 低空间不调用停机、不清理旧数据 | 通过 |
| dump、tar、镜像记录、服务重启失败 | 通过；服务重启尝试/失败状态与完成标记正确 |
| 首次恢复门槛、周检失败保留上次成功记录 | 通过 |
| 清单覆盖、路径穿越、损坏/不完整/陌生目录、外部符号链接 | 通过；拒绝或保留待查 |
| 调度锁与手动维护锁、stdin 被 Docker 读取回归 | 通过 |
| 恢复项目、卷、网络、端口、挂载隔离 | 通过 |
| Bash 语法、日/周 systemd 时间表达式 | 通过 |
| systemd unit 结构 | 通过；WSL 无 docker.service，临时目录使用依赖桩，仅验证结构，未安装/启动服务 |
| Git 空白检查 | 通过 |

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
bash -n tools/server-snapshot.sh tools/check-server-snapshot.sh docs/fixtures/p1-07/rehearse.sh
systemd-analyze calendar '*-*-* 04:00:00 Asia/Shanghai'
systemd-analyze calendar 'Sun *-*-* 05:00:00 Asia/Shanghai'
```

CI 已加入工具回归和真实恢复步骤，尚未推送触发远端运行。此次只修改运维工具和文档，未重跑前后端产品测试。

## 真实 Docker 演练

先用既有 P0-13 本地镜像完成一次，再将当前仓库已构建、包含 V1～V19 的 JAR 放入独立演练镜像完成同一轮。最终验收使用 `interview-flashcards-backend:p107-rehearsal`、PostgreSQL 18.3 Alpine 及现有 Web 镜像；独立 `interview-flashcards-p107-rehearsal` 项目和全新卷，未访问现有应用数据库。TLS 为仅本地的 localhost 证书，通过显式可信 CA 验证，未跳过校验。

| 操作 | 结果 |
| --- | --- |
| 全新数据库迁移到 V19，写入两行 Unicode/历史日期合成表、原始 PDF、暂存探针 | 成功 |
| 首次完整备份及实际恢复 | 57 张 public 表（含验收表）逐表数量/内容指纹一致；PDF 与暂存文件完整匹配；恢复应用 TLS/数据库健康 |
| 连续生成三份，测试策略 keep_count=2 | 中间旧副本被删除，首次已恢复点与最新副本保留 |
| 显式执行周期 verify | 最新副本恢复通过，last_verified 更新至最新 |
| status | healthy=true，2 份、6,212,619 字节，无失败残留 |
| 清理测试环境 | 原模拟部署及两次演练的容器/网络/卷均已删除，私有快照/日志留在 ignored output 供复核 |

合成数据验证备份机制，未进行真实用户生产回退或新的实体手机验收。PDF SHA-256 为 `f19b0c9343479eef39298c24f8fee07dc6f2578e8615ffb69e8de45e28171055`。最终状态见 [脱敏验收结果](acceptance-result.json)。完整私有证据在本地 `output/p1-07-live-VyUPdIbj`，其中包括首次/末次恢复报告、快照与运行日志；生产密码、证书私钥及整站快照未提交。

在一次性 Linux Docker 引擎复现（先构建本地后端/Web 镜像）：

```bash
bash docs/fixtures/p1-07/rehearse.sh
# 或指定含当前构建的后端镜像
FC_TEST_BACKEND_IMAGE=interview-flashcards-backend:your-local-build bash docs/fixtures/p1-07/rehearse.sh
```

脚本拒绝已有的测试或恢复项目资源，端口仅 `127.0.0.1:18087/18447` 和 `18082/18445`，成功后删除其专用测试卷；失败则保留现场。默认使用 p013-local 镜像名，CI 在同一工作流中已经构建当前版本，个人环境需先确认镜像内容。

## 上线与限制

无 Flyway/个人 ZIP 变更。按[自动备份手册](../../runbooks/automatic-backups.md)调整容量、维护用户/路径，选择停机窗口先执行首次备份及实际恢复，再启用 timer。每次会短暂停写并使既有登录会话失效，设备未同步内容须先提交。镜像、证书凭据和异地副本需另行保管；本机定时快照不能代替异地容灾。真正生产回退仍需明确恢复时间点及代次切换。

决策见 [ADR 0027](../../decisions/0027-p1-07-automatic-server-backups.md)。
