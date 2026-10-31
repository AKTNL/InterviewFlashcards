# Frontend

Vue 3 / TypeScript / Vite / Vue Router / Pinia。使用 Node 24.13.0 和 `npm ci` 复现锁定依赖。启动与检查见 [开发手册](../docs/runbooks/local-development.md)。

- `router`：首页和客户端 404，按页面懒加载。
- `api`：同源请求、超时、统一成功/错误信封，保留请求编号，不自动重放修改。
- `stores/service`：真实健康状态，失败时清除旧成功结果，显式重试并防止重复请求。
- `views`：响应式应用壳、连接检查与空学习空间；没有假题库、假登录或假同步状态。

源码开发使用 Vite `/api/` 代理；Compose 使用 Nginx 同源反向代理和 SPA 路由回退。浏览器 Cookie 不跨域发送。
