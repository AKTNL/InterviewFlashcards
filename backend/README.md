# Backend

Java 21 / Spring Boot 3.5.13 的单进程模块化应用。启动和验证见 [开发手册](../docs/runbooks/local-development.md)，响应契约见 [基础 API](../docs/api/baseline.md)。

按功能分包，保持模块边界，不为尚未实现的业务建立空接口或虚构实体：

- `common/config`：环境配置、Spring Security、UTC Clock 和固定学习时区。
- `common/time`：可注入 Clock 的学习日与时间戳，后续业务复用。
- `common/web`：请求追踪、响应信封、错误码、校验与异常脱敏。
- `health`：只读数据库和迁移基线检查。

P0-03 起按 `identity`、`source`、`imports`、`library`、`learning`、`sync`、`backup` 建立业务模块；控制器、应用服务、实体/仓储留在各自模块内。个人规模下保持单部署单数据库，不拆微服务或引入消息队列。

数据库结构仅由 Flyway 演进。`V1__application_baseline.sql` 是基础设施表，包含唯一安装 ID 和 UTC 创建时间；健康检查依赖该基线。用户归属、唯一约束和实体从 P0-03 的新迁移开始。JPA 使用 `ddl-auto=validate`，禁止自动改表；Flyway clean 被禁用。

配置均通过 `application.yml` 里的环境变量入口提供；密码无默认值。`app.storage` 在本任务中定义持久化与临时目录配置，P0-03 再实现存储接口和鉴权文件流。
