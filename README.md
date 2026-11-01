# InterviewFlashcards

Java 后端面试卡片学习网站。P0-01～P0-13 已完成：工程与登录、PDF 上传解析、幂等写入与同步队列、逐章检查确认、题库检索与完整阅读、笔记收藏、纠错与 PDF 定位、三档评分与调度，以及每日计划、学习设置、批量复习、会话恢复和基础统计。全范围修订刷新、断网重试、双设备冲突处理、评分候选更正及完整 ZIP 备份与安全替换恢复已完成。P0-12 已补齐手机/桌面布局、异常恢复、未保存保护及题库删除/进度重置。P0-13 已完成公网 HTTPS 部署、实体手机登录与双端同步、证书续期演练、一致快照及隔离恢复；应用内 ZIP 恢复与实体手机旧队列重连已通过，恢复及重连后全部业务数据和原 PDF 核对一致，P0-13/M5 已签收。

P1-08 已完成仓库实现及本地验收：邀请码注册、独立账号、密码管理、旧账号保留、跨标签页队列隔离与服务端配额。已随 P1-10 发布公网；邀请码注册保持关闭。

P1-09 已完成仓库实现及本地验收：在“题库分享”核对完整 PDF 范围后，向指定账号发送一次性内容副本邀请；双方各自编辑和复习，保留来源版本。邀请可过期或撤销，领取幂等且失败回滚。已随 P1-10 发布公网。

P1-10 已完成仓库实现及本地验收：在“学习小组”受邀加入，自愿逐组授权学习摘要；摘要默认关闭，可随时撤回或退出。今日新学/复习和近一周学习天数复用有效评分统计，个人恢复不会重新加入或恢复已撤回授权。2026-11-01 已发布公网，V11～V13 升级、原有数据保留与 HTTPS 检查通过，实体设备上的新功能操作待用户复验。

P1-11 已完成仓库实现及本地验收：个人中心“我的邀请码”可受控生成、复制、查看状态与撤销；好友先验证邀请再设置账号，注册后预填用户名登录。已补齐邀请权限、累计成功额度、全站注册上限和并发保护，旧维护邀请码兼容。2026-11-01 已发布公网，V14、原数据保留及 HTTPS 检查通过；注册保持关闭，个人邀请权限待维护者明确授权。

P1-12 已完成仓库实现与本地验收：独立管理员维护入口、管理后台、用户建号/启停/首次改密与重置、邀请权限及额度配置、逐请求鉴权、管理版本及审计；管理建号与邀请码注册共享全站上限。尚未发布公网，线上账号权限未调整。P1-13 已完成平台知识库新增、PDF 导入检查、发布/下架/删除、公共预览和独立副本领取；V16 与本地验收见 [P1-13 记录](docs/fixtures/p1-13/README.md)。P1-14 已完成管理 Dashboard、逐用户/每日汇总、前台活动心跳及跨设备去重时长；V17、统计口径和本地验证见 [P1-14 记录](docs/fixtures/p1-14/README.md)，尚未发布公网。

- [P1-14 Dashboard API](docs/api/admin-dashboard.md)
- [P1-12 验收记录](docs/fixtures/p1-12/README.md)
- [管理员与用户管理 API](docs/api/admin-accounts.md)
- [P1-11 公网升级记录](docs/fixtures/p1-11/public-deployment-2026-11-01.md)
- [P1-11 验收记录](docs/fixtures/p1-11/README.md)
- [个人邀请与分步注册 API](docs/api/personal-invitations.md)
- [P1-08～P1-10 公网升级记录](docs/fixtures/p1-10/public-deployment-2026-11-01.md)
- [P1-10 验收记录](docs/fixtures/p1-10/README.md)
- [学习小组 API](docs/api/study-groups.md)
- [P1-09 验收记录](docs/fixtures/p1-09/README.md)
- [题库分享 API](docs/api/deck-sharing.md)
- [P1-08 验收记录](docs/fixtures/p1-08/README.md)
- [账号与邀请码维护](docs/runbooks/accounts.md)
- [注册、密码与容量 API](docs/api/registration-and-accounts.md)
- [设计文档](docs/设计文档.md)
- [P1-01 阅读设置、快捷键与返回位置验收](docs/fixtures/p1-01/README.md)
- [P1-02 相似题提示、异常集中处理与原文并排对照验收](docs/fixtures/p1-02/README.md)（仓库实现与本地验收完成，尚未发布公网）
- [P1-03 按周学习记录、遗忘排序与章节变化验收](docs/fixtures/p1-03/README.md)（仓库实现与本地验收完成，尚未发布公网）
- [P1-04 复习参数配置与效果评估验收](docs/fixtures/p1-04/README.md)（仓库实现与本地验收完成，尚未发布公网）
- [个人复习参数与效果观察 API](docs/api/review-parameters.md)
- [个人学习趋势 API](docs/api/learning-trends.md)
- [开发文档与任务顺序](docs/开发文档.md)
- [P0-01 验收记录与回归基线](docs/fixtures/p0-01/README.md)
- [首版技术与数据规则决策](docs/decisions/0001-p0-01-baseline.md)
- [本地启动与验证](docs/runbooks/local-development.md)
- [基础 API 与错误码](docs/api/baseline.md)
- [P0-02 验收记录](docs/fixtures/p0-02/README.md)
- [P0-03 验收记录](docs/fixtures/p0-03/README.md)
- [登录与文件 API](docs/api/auth-and-files.md)
- [P0-04 验收记录](docs/fixtures/p0-04/README.md)
- [写入与同步 API](docs/api/mutations-and-sync.md)
- [P0-05 验收记录](docs/fixtures/p0-05/README.md)
- [解析任务与草稿 API](docs/api/imports-and-drafts.md)
- [P0-06 验收记录](docs/fixtures/p0-06/README.md)
- [逐章检查与确认 API](docs/api/chapter-review.md)
- [P0-07 验收记录](docs/fixtures/p0-07/README.md)
- [题库与来源阅读 API](docs/api/card-library.md)
- [P0-08 验收记录](docs/fixtures/p0-08/README.md)
- [学习与评分 API](docs/api/learning.md)
- [P0-09 验收记录](docs/fixtures/p0-09/README.md)
- [每日计划与学习设置 API](docs/api/daily-learning.md)
- [P0-10 验收记录](docs/fixtures/p0-10/README.md)
- [跨设备同步与评分冲突 API](docs/api/cross-device-sync.md)
- [P0-11 实现与验收记录](docs/fixtures/p0-11/README.md)
- [完整备份与安全恢复 API](docs/api/backups-and-restores.md)
- [P0-12 实现与验收记录](docs/fixtures/p0-12/README.md)
- [题库删除与学习进度重置 API](docs/api/deck-data.md)
- [P0-13 本地集成与发布验收](docs/fixtures/p0-13/README.md)
- [HTTPS 发布、维护与回退手册](docs/runbooks/release.md)
- [2026-11-01 公网部署与服务器恢复记录](docs/fixtures/p0-13/public-deployment-2026-11-01.md)
- [2026-11-01 公网 ZIP 恢复与旧设备重连验收](docs/fixtures/p0-13/public-restore-2026-11-01.md)
- [当前 Debian 部署的日常操作](docs/runbooks/debian-home-deployment.md)

快速启动（Docker Compose v2，在仓库根目录）：

```powershell
Copy-Item deploy/.env.example deploy/.env
# 按本地开发手册生成 BCrypt hash，填写 POSTGRES_PASSWORD、ACCOUNT_USERNAME、ACCOUNT_PASSWORD_HASH
docker compose --env-file deploy/.env -f deploy/compose.yml up --build -d --wait
```

访问 [应用](http://127.0.0.1:8088/) 和 [健康检查](http://127.0.0.1:8088/api/health)。数据库、来源文件和临时文件使用独立命名卷。源码启动、测试及数据保留说明见开发手册。

本地验证（Java 21、Maven 3.9+、Python 3.10+；首次运行需下载依赖）：

```powershell
./tools/pdf-probe/run.ps1 -RenderSamples
```

生成报告位于 `output/pdf-probe/report.md`。详细说明见 [PDF 验证工具](tools/pdf-probe/README.md)。
