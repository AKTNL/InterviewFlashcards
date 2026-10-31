# InterviewFlashcards

Java 后端面试卡片学习网站。P0-01～P0-07 已完成：工程与登录、PDF 上传解析、幂等写入与同步队列、逐章检查确认、全题库检索筛选、完整答案阅读、笔记收藏、内容与技术纠错，以及内置 PDF 定位核对。下一项为 P0-08 评分、复习算法与撤销。

- [设计文档](docs/设计文档.md)
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
