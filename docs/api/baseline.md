# P0-02 基础 API 契约

后端和前端统一使用同源 `/api/`。当前仅开放 `GET /api/health`，其他请求默认拒绝。P0-03 将加入会话登录、CSRF token 获取与业务接口的授权规则；本任务不提供注册或临时测试账号。

## 响应与请求追踪

成功：

```json
{
  "data": {
    "status": "UP",
    "database": "UP",
    "learningTimezone": "Asia/Shanghai",
    "learningDate": "2026-10-31"
  },
  "meta": {
    "requestId": "136059ef-f395-4bfb-9658-7680c2c466e9",
    "serverTime": "2026-10-31T03:00:00Z"
  }
}
```

失败：

```json
{
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "服务暂时不可用，请稍后重试",
    "fields": []
  },
  "meta": {
    "requestId": "136059ef-f395-4bfb-9658-7680c2c466e9",
    "serverTime": "2026-10-31T03:00:00Z"
  }
}
```

每个请求的 `X-Request-ID` 响应头与 `meta.requestId` 一致。服务器生成 UUID，不信任或回显传入的同名请求头。前端展示错误说明及请求编号，不展示堆栈、SQL、代理 HTML 或原始请求内容。`fields` 仅包含校验失败的字段名称，不包含拒绝值。

`serverTime` 是 UTC ISO 8601 时间戳；`learningDate` 由服务端按固定 `Asia/Shanghai` 时区生成。日期不从浏览器时区推算。未来实体时间戳使用 PostgreSQL `TIMESTAMPTZ`，到期学习日使用 `DATE`。

## 健康检查

`GET /api/health` 会查询 Flyway 创建的 `application_metadata` 基线记录，数据库或基线不可用时返回 HTTP 503，不返回静态成功。数据库启动失败、迁移失败时应用直接启动失败，不绕过迁移。

不返回数据库地址、密码、文件路径、安装标识或用户信息。响应禁止缓存（Spring Security 默认响应头）。

## 已实现错误码

| HTTP | code | 处理方式 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 修正 `fields` 中的字段 |
| 400 | `INVALID_REQUEST` | 修正 JSON 或请求格式 |
| 401 | `UNAUTHENTICATED` | 需认证；登录将在 P0-03 实现 |
| 403 | `FORBIDDEN` | 拒绝访问或 CSRF 校验失败 |
| 404 | `NOT_FOUND` | 资源不存在 |
| 405 | `METHOD_NOT_ALLOWED` | 修正请求方法 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 修正 Content-Type |
| 503 | `SERVICE_UNAVAILABLE` | 可以由用户发起重试 |
| 500 | `INTERNAL_ERROR` | 凭请求编号排查 |

路由首先经过安全过滤器：当前未开放的路径即使不存在，也优先返回 401；缺少 CSRF 的写请求优先返回 403。表中的 MVC 错误码用于经过授权的控制器请求。版本冲突、幂等与恢复代次错误在 P0-04 固化，不提前模拟实现。

前端额外区分 `NETWORK_ERROR`、`REQUEST_TIMEOUT`、`REQUEST_CANCELLED`、`INVALID_RESPONSE`。统一客户端设置 10 秒超时，不自动重放请求；网络、超时与 503 可提示重试，认证、校验与冲突不可统一盲目重试。反向代理 5xx 的非 JSON 响应显示通用服务不可用说明。

## 日志

请求日志仅记录请求编号、HTTP 方法、状态和耗时。异常仅记录类型，不记录异常 message/堆栈中的个人内容。默认 INFO 日志关闭请求详情、SQL 和参数绑定；Nginx 不记录 URL/查询串。禁止在业务代码中追加密码、Cookie、正文或完整个人内容日志。数据库配置或依赖初始化失败可通过启动日志排查；不要以 DEBUG 模式收集真实用户内容。
