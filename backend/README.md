# Backend

Java 21 / Spring Boot 3.5.13 的单进程模块化应用。启动和验证见 [开发手册](../docs/runbooks/local-development.md)，响应契约见 [基础 API](../docs/api/baseline.md)。

按功能分包，保持模块边界，不为尚未实现的业务建立空接口或虚构实体：

- `common/config`：环境配置、Spring Security、UTC Clock 和固定学习时区。
- `common/time`：可注入 Clock 的学习日与时间戳，后续业务复用。
- `common/web`：请求追踪、响应信封、错误码、校验与异常脱敏。
- `health`：只读数据库和迁移基线检查。
- `auth`：预置用户、BCrypt、会话资料和 CSRF。
- `files`：PDF 验证、流式指纹、用户内去重、存储封装、鉴权 Range/HEAD 读取。
- `tools`：不连接数据库的交互式密码 hash 工具。
- `sync`：统一业务事务入口、请求摘要和幂等收据、用户写屏障、修订/快照/收据查询。

后续按 `imports`、`library`、`learning`、`backup` 建立业务模块；所有业务修改复用 `sync/MutationService.execute`。控制器、应用服务、实体/仓储留在各自模块内。个人规模下保持单部署单数据库，不拆微服务或引入消息队列。

数据库结构仅由 Flyway 演进。V1 是基础设施元数据；V2 建立用户、来源和各业务实体的基础表及复合归属外键；V3 增加来源版本和收据摘要约束。收据与修订使用同事务 JDBC，来源等实体使用 JPA；详见 [写入与同步协议](../docs/api/mutations-and-sync.md)。JPA 使用 `ddl-auto=validate`，禁止自动改表；Flyway clean 被禁用。

配置均通过 `application.yml` 里的环境变量入口提供；数据库密码、账号和 BCrypt hash 无默认值。来源文件不由静态目录暴露；存储与临时根目录必须分离。详见 [P0-03 决策](../docs/decisions/0003-p0-03-identity-and-storage.md)。
