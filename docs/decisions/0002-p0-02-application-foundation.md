# ADR 0002：P0-02 工程与开发环境

- 日期：2026-10-31
- 状态：采用
- 依据：开发文档 §4.3、ADR 0001

## 依赖和模块

前端锁定 Vue 3.5.44、Vue Router 4.6.4、Pinia 3.0.4、TypeScript 6.0.3、Vite 8.3.5、Vitest 5.0.5；完整工具链及传递依赖见 `frontend/package-lock.json`。标准 npm 仓库 URL 与 integrity 一并提交，使用 `npm ci`，不依赖作者机器上的全局包或镜像设置。Node 运行基线为 24.13.0。

后端使用 Java 21、Spring Boot 3.5.13，Boot BOM 管理 Spring Security、Bean Validation、JPA、PostgreSQL JDBC 和 Maven 插件版本。Flyway 单独锁定 11.20.0：Boot 默认的版本对 PostgreSQL 18 提示超出已测试范围，此版本已通过 PostgreSQL 18 空库及重启回归。数据库容器锁定 `postgres:18.3-alpine`，卷挂载采用 PostgreSQL 18 的新目录约定。

后端采用按功能分包的模块化单体，当前只实现 `common` 和 `health`；用户、来源、导入、题库、学习、同步和备份按后续任务引入。前端仅有真实应用壳和服务连接检查，未提前实现假业务流程。

## 数据和请求边界

- Flyway 是结构唯一写入者，JPA `validate`、禁用 Flyway clean。V1 仅创建基础设施元数据和稳定安装 ID，业务表由 P0-03 的新迁移引入。
- 健康检查读取实际数据库基线，失败返回 503。匿名仅放行只读健康检查，其他路径默认拒绝，写请求保留 CSRF 保护。没有临时账号和内置密码。
- API 成功/错误信封与响应头共享服务器生成的请求编号。日志只记录编号、方法、状态、耗时和异常类型，不记录请求正文或异常中的个人内容。
- 注入 UTC Clock；学习时区固定 `Asia/Shanghai`，数据库连接 UTC，时间戳输出 ISO 8601。禁止使用浏览器日期代替学习日。
- 前端统一客户端保留认证、校验、冲突及网络错误区别，不自动重放写入。幂等/版本/代次/待同步队列留 P0-04。

## 开发与验证

源码模式由 Vite 代理 `/api`，Compose 模式由 Nginx 同源代理。数据库、文件、临时文件使用分开的命名卷，应用容器以普通用户运行；凭据通过环境注入，示例密码为空。开发 HTTP 仅绑定回环地址，公网 HTTPS 在 P0-13 完成。

CI 分前端、真实 PostgreSQL 后端和完整 Compose 三项检查。后端集成测试每次创建随机空 schema，完整启动和关闭两次，验证数据库、迁移、时间与安全响应；不以 H2 代替 PostgreSQL。Compose 检查在删除并重建容器后比对安装 ID 和文件标记，使用独立的可丢弃测试卷。

Windows 本机 JDK 21 的内部 Unix socket 使用独立临时目录，过长/不可用的路径可能造成 `Invalid argument: connect`。已验证将 `jdk.net.unixdomain.tmpdir` 指向短本地目录可以启动；不将这一机器特定路径写入应用配置或 Linux 容器。

容器内 Maven 使用官方备用入口 `https://repo1.maven.org/maven2`，支持通过 `MAVEN_MIRROR_URL` 构建参数覆盖；不修改主机 Maven 配置。原因是本次容器网络对 `repo.maven.apache.org` 发生 TLS 重置，备用官方入口已实测可访问。BuildKit cache mount 保留依赖，避免修改源码后重新下载所有依赖。
