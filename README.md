# InterviewFlashcards

Java 后端面试卡片学习网站。P0-01、P0-02 已完成：解析基线、Vue/TypeScript、Spring Boot、PostgreSQL/Flyway 应用工程、Compose 和 CI 已建立并完成本地验收；登录与业务模型从 P0-03 开始。

- [设计文档](docs/设计文档.md)
- [开发文档与任务顺序](docs/开发文档.md)
- [P0-01 验收记录与回归基线](docs/fixtures/p0-01/README.md)
- [首版技术与数据规则决策](docs/decisions/0001-p0-01-baseline.md)
- [本地启动与验证](docs/runbooks/local-development.md)
- [基础 API 与错误码](docs/api/baseline.md)
- [P0-02 验收记录](docs/fixtures/p0-02/README.md)

快速启动（Docker Compose v2，在仓库根目录）：

```powershell
Copy-Item deploy/.env.example deploy/.env
# 编辑 deploy/.env，填写自行生成的非空 POSTGRES_PASSWORD
docker compose --env-file deploy/.env -f deploy/compose.yml up --build -d --wait
```

访问 [应用](http://127.0.0.1:8088/) 和 [健康检查](http://127.0.0.1:8088/api/health)。数据库、来源文件和临时文件使用独立命名卷。源码启动、测试及数据保留说明见开发手册。

本地验证（Java 21、Maven 3.9+、Python 3.10+；首次运行需下载依赖）：

```powershell
./tools/pdf-probe/run.ps1 -RenderSamples
```

生成报告位于 `output/pdf-probe/report.md`。详细说明见 [PDF 验证工具](tools/pdf-probe/README.md)。
