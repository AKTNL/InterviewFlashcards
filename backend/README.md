# Backend

Java 21 / Spring Boot 3.5.13 的单进程模块化应用。启动和验证见 [开发手册](../docs/runbooks/local-development.md)，响应契约见 [基础 API](../docs/api/baseline.md)。

按功能分包，保持模块边界，不为尚未实现的业务建立空接口或虚构实体：

- `common/config`：环境配置、Spring Security、UTC Clock 和固定学习时区。
- `common/time`：可注入 Clock 的学习日与时间戳，后续业务复用。
- `common/web`：请求追踪、响应信封、错误码、校验与异常脱敏。
- `health`：只读数据库和迁移基线检查。
- `auth`：预置身份绑定、邀请码注册、BCrypt、密码管理、会话版本撤销、限流与 CSRF。
- `files`：PDF 验证、流式指纹、用户内去重、存储封装、鉴权 Range/HEAD 读取。
- `tools`：离线密码 hash 工具，以及连接数据库的受控邀请码生成/撤销与交互密码重置工具。
- `sync`：统一业务事务入口、请求摘要和幂等收据、用户写屏障、修订/快照/收据查询。
- `importing`：PDFBox 目录和正文边界解析、持久化任务、提取证据检查点、失败/重启恢复、草稿事务保存和归属查询。
- `sharing`：P1-09 内容专用快照、绑定账号的限时一次性邀请、完整 PDF 清单、独立文件与新 ID、来源版本、幂等领取、撤销和失败回滚。接口与验收见 [题库分享 API](../docs/api/deck-sharing.md)。

后续按 `library`、`learning`、`backup` 建立业务模块；用户业务修改复用 `sync/MutationService.execute`，后台任务也遵守用户锁/代次屏障。控制器、应用服务、实体/仓储留在各自模块内。个人规模下保持单部署单数据库，不拆微服务或引入消息队列。

数据库结构仅由 Flyway 演进。V1 是基础设施元数据；V2 建立用户、来源和各业务实体的基础表及复合归属外键；V3 增加来源版本和收据摘要约束；V4 增加任务证据/报告/执行令牌、草稿原始内容与来源元数据，并为历史来源补建任务。收据、导入与修订使用同事务 JDBC，来源等实体使用 JPA；详见 [写入与同步协议](../docs/api/mutations-and-sync.md) 和 [解析任务 API](../docs/api/imports-and-drafts.md)。JPA 使用 `ddl-auto=validate`，禁止自动改表；Flyway clean 被禁用。

配置均通过 `application.yml` 里的环境变量入口提供；数据库密码、账号和 BCrypt hash 无默认值。来源文件不由静态目录暴露；存储与临时根目录必须分离。详见 [P0-03 决策](../docs/decisions/0003-p0-03-identity-and-storage.md)。
