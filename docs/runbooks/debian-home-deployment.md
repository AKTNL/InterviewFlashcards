# 当前 Debian 公网部署的日常操作

本手册对应 2026-11-01 已部署环境，实际证据及当前限制见 [公网部署记录](../fixtures/p0-13/public-deployment-2026-11-01.md)。

访问 `https://study.aktnl.dpdns.org:18443`。家中 Windows 使用 hosts 内网解析；离开家中网络时停用该行。服务器内部地址 `192.168.1.7` 当前仍是 DHCP 分配，变化后需要更新端口映射和内网解析。

## 服务管理

SSH：`ssh kevin@192.168.1.7 -p 2222`。在服务器定义命令简写：

```bash
fcprod() {
    docker compose -p interview-flashcards \
      --env-file /srv/interview-flashcards/deploy/.env.production \
      -f /srv/interview-flashcards/deploy/compose.production.yml "$@" </dev/null
}
fcprod ps
fcprod logs --tail=100 backend
```

正常启动使用已导入的冻结镜像：

```bash
fcprod up -d --no-build --pull never --wait --wait-timeout 240
```

停止时使用 `fcprod stop`。需要移除容器时，`fcprod down` 保留数据卷；生产环境不添加 `-v`。项目名保持 `interview-flashcards`，后端重启后重新登录。

Docker Hub 的 DNS/连通问题尚未修复。当前发布使用 Windows 离线构建后导入的镜像，保留 `output/release` 中的源码包、镜像包及对应 SHA-256 文件。未来升级使用新的不可变 tag，保存旧镜像和配置。

## 检查入口

服务器本机 HTTPS 检查保留真实证书域名：

```bash
curl --noproxy '*' --fail \
  --resolve study.aktnl.dpdns.org:18443:127.0.0.1 \
  https://study.aktnl.dpdns.org:18443/api/health
```

完整公网检查在家中网络之外执行，例如 Windows 临时连接手机热点：

```powershell
python D:\InterviewFlashcards\tools\release-check.py `
  --base-url https://study.aktnl.dpdns.org:18443 `
  --http-url http://study.aktnl.dpdns.org:18080
```

## 备份及恢复演练

日常在应用中导出完整 ZIP，下载到服务器之外保存。升级前暂停所有设备写入，再在服务器运行一致快照：

```bash
cd /srv/interview-flashcards
bash tools/server-snapshot.sh server-snapshot-YYYYMMDD-01
```

每次使用新的标签。脚本拒绝覆盖非空目录，停止 Web/后端并保持数据库运行，捕获数据库、文件卷、暂存卷和当前部署配置，校验后自动恢复服务。快照目录为 `output/标签`，含私密数据及 `.env.production`，保持 700/600 权限并保存到服务器之外的受限目录。

验证已保存的快照，不切换正式入口：

```bash
bash tools/check-server-snapshot.sh server-snapshot-YYYYMMDD-01
```

恢复检查使用固定的独立项目 `interview-flashcards-recovery-check`，只绑定 `127.0.0.1:18082/18445`。它拒绝选用已有演练资源或生产卷，校验所有数据库表与文件，启动恢复应用并验证 TLS/数据库健康。成功后移除本次演练资源，保留受限的报告目录；失败时保留现场供检查。

该脚本只验证隔离恢复。真正回退生产版本时，先停写并保留新旧完整快照，确认 schema/镜像兼容性，再按 [通用发布手册](release.md#回退) 在新卷恢复、切换用户数据代次并验证后切换入口。

## 证书续期

证书由 Certbot 的 DNS 验证续期。root 凭据文件 `/etc/letsencrypt/cloudflare-study.ini`，部署钩子 `/etc/letsencrypt/renewal-hooks/deploy/50-interview-flashcards`；钩子同步 `/srv/interview-flashcards/tls` 并重载两个 Nginx。无需因证书续期重启后端。

```bash
systemctl status certbot.timer --no-pager
sudo certbot renew --cert-name study.aktnl.dpdns.org --dry-run --run-deploy-hooks
```

保留已有的 Cloudflare Token 权限和凭据文件。系统 Python 插件的弃用预告已在本次演练中观察到，DNS 续期及钩子实测成功；通过 Debian 包管理维护该工具链。

## P1-08 后续升级

当前公网记录仍对应原发布版本。P1-08 已在仓库完成，尚未部署；下一次升级按 [发布手册](release.md) 保存完整快照并执行 V11，旧账号与密码保持。注册默认关闭；邀请码生成/撤销、交互密码重置和限额配置见 [账号维护手册](accounts.md)。不能再通过改环境 hash 并重启来改密码。
