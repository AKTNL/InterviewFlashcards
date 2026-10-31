# P0-03 会话与文件 API

所有接口同源，JSON 响应沿用 [基础信封](baseline.md)。不提供注册接口；用户身份来自服务端会话，接口不接受客户端指定的 `userId`。

## 登录与 CSRF

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/auth/csrf` | 无需登录；返回 `{headerName, token}`，创建匿名会话 |
| POST | `/api/auth/login` | `application/x-www-form-urlencoded` 的 `username`、`password`；成功返回用户资料 |
| GET | `/api/auth/me` | 返回 `{id, username, learningTimezone, dataEpoch, revision}`；未登录/过期返回 401 |
| POST | `/api/auth/logout` | 验证 CSRF、销毁当前会话并删除 Cookie，返回 `data: true` |

写请求（包括登录、退出、multipart 上传）必须先获取 CSRF，并将返回的 token 放入 `headerName` 指定的请求头。使用 Spring Security 默认的会话 token 和掩码处理，不自行实现随机令牌或密码比较。登录、退出后旧 token 失效，下一次写请求重新获取；403 `CSRF_INVALID` 后清除客户端 token，下次用户主动重试重新获取，**不自动重放写入**。

Cookie 仅用于同源请求：`HttpOnly`、`SameSite=Lax`、仅 Cookie 跟踪，不允许 URL 携带会话 ID。登录成功更换会话 ID。默认闲置 30 分钟过期，`SESSION_TIMEOUT` 可配置。当前进程内会话不跨后端重启保存，重启后重新登录，用户及文件保持不变。设备各自拥有会话，退出一个设备不会退出另一设备。

公网入口必须使用 HTTPS，并设置 `SESSION_COOKIE_SECURE=true`；当前 Compose 是本地 HTTP 开发环境，完整证书/域名部署仍由 P0-13 交付。密码和 token 不存入 LocalStorage、IndexedDB 或日志。密码错误统一返回 401 `BAD_CREDENTIALS`，不提示账号是否存在。

## 来源与图片

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/sources` | 返回当前用户的来源数组，按创建时间降序 |
| POST | `/api/sources` | `multipart/form-data`，单个 `file` 字段；返回 `{source, duplicate}` |
| GET | `/api/sources/{id}` | 当前用户的来源元数据 |
| GET / HEAD | `/api/sources/{id}/content` | 当前用户的 PDF 原始文件流 |
| GET / HEAD | `/api/assets/{id}/content` | 当前用户的 PNG/JPEG 资源流 |

来源字段：`id`、`originalFilename`、`sha256`、`pageCount`、`byteSize`、`createdAt`，不暴露物理路径、存储键或他人归属信息。不存在和属于他人的 ID 均返回 404；未登录先返回 401。图片的生成与登记属于 P0-05，本任务交付存储元数据和受保护读取入口，不开放任意图片上传。

上传限制：单文件 50 MiB，multipart 请求 51 MiB。文件名以 `.pdf` 结尾，MIME 为 `application/pdf` 或浏览器缺省的 `application/octet-stream`；同时检查 `%PDF-` 标识和 PDFBox 可读取性、页树、正页数，拒绝加密文件（包括空密码加密）和空文件。流式计算 SHA-256，文件名只保留清理后的显示名；服务端生成 UUID 作为存储键。

SHA-256 在用户空间内唯一；同用户重复上传即使文件名改变也返回原来源和 `duplicate: true`，不新增文件。并发上传通过用户行锁和唯一约束得到相同来源；不同用户可各自保存同一 PDF。P0-05 再在此来源上建立/返回解析任务，不将 P0-03 的文件保存宣称为解析成功。

二进制接口不使用 JSON 信封，返回正确 `Content-Type`、UTF-8 显示文件名的 `Content-Disposition: inline`、`Cache-Control: no-store`，支持 `Range` 的 206 与无效范围的 416，以及 HEAD。范围处理复用 Spring MVC `Resource`，不在 Java 堆内读取完整 PDF。非二进制错误仍使用统一错误信封；无效 Range 由 MVC 返回 416。

## 新增错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 401 | `BAD_CREDENTIALS` | 账号或密码不正确 |
| 403 | `CSRF_INVALID` | 缺少或过期的 CSRF，重新获取后由用户重试 |
| 400 | `INVALID_PDF` | 空、格式不符、不可读、加密或无页面的 PDF |
| 413 | `FILE_TOO_LARGE` | 超出上传限制 |
| 503 | `STORAGE_UNAVAILABLE` | 存储不可用或已登记的原文件缺失 |

Nginx 也限制 51 MiB；代理拒绝的超大请求可能使用代理错误响应。前端先校验文件大小，并且不会显示代理 HTML。
