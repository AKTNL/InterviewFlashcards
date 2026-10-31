# Frontend

Vue 3 / TypeScript / Vite / Vue Router / Pinia。使用 Node 24.13.0 和 `npm ci` 复现锁定依赖。启动与检查见 [开发手册](../docs/runbooks/local-development.md)。

- `router`：首页、登录、受保护资料页和客户端 404，按页面懒加载。
- `api`：同源请求、超时、统一成功/错误信封，保留请求编号，不自动重放修改。
- `stores/service`：真实健康状态，失败时清除旧成功结果，显式重试并防止重复请求。
- `stores/auth`：服务端会话资料、登录退出和过期处理，不持久化密码或 token。
- `api/client`：写请求前获取 CSRF，认证变化后刷新 token，不自动重放写入。
- `views`：响应式应用壳、登录、PDF 上传去重与资料列表；解析、检查和学习将在后续任务开放。
- `sync`、`stores/sync`：IndexedDB 持久化 PDF Blob/编辑与操作 ID，代次/收据/版本预检、退避重试及账号隔离；页面激活和联网时刷新服务端快照。
- `components/SyncPanel`：待同步、同步中、已同步、失败、待处理；冲突保留双方值，显式新操作提交，旧代次不重放。基础客户端不重放请求，业务队列按 [写入协议](../docs/api/mutations-and-sync.md) 管理重试。

源码开发使用 Vite `/api/` 代理；Compose 使用 Nginx 同源反向代理和 SPA 路由回退。浏览器 Cookie 不跨域发送。
