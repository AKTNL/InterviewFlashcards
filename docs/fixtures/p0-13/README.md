# P0-13 · 本地集成验收与 HTTPS 发布交付

状态：已完成（2026-11-01），P0-13/M5 已签收。公网部署、实体手机登录与双端同步、续期演练及服务器一致快照隔离恢复见[公网部署记录](public-deployment-2026-11-01.md)；此前暂缓的应用内 ZIP 恢复和实体手机旧队列重连也已通过，见[公网恢复与重连记录](public-restore-2026-11-01.md)。恢复及手机重连后均逐项比较 23 张业务表、485 行及原 PDF，通过。下文保留 2026-10-31 本地验收的历史范围，其中“待签收”是当时状态，后续完成情况以上述记录为准。

## 交付

- 独立 [生产 Compose](../../../deploy/compose.production.yml)、TLS 代理模板、发布环境示例；数据库/后端不暴露端口、强制安全会话 Cookie、HTTPS 跳转和静态资源规则。
- [只读发布检查](../../../tools/release-check.py)：真实验证证书/主机名、跳转、HSTS/MIME/缓存、鉴权、数据库和请求追踪，不提供跳过 TLS 校验选项。
- [完整运维手册](../../runbooks/release.md)：启动、证书续期、健康检查、日志、文件保存、清理、停机、升级迁移、一致快照和回退；[决策](../../decisions/0013-p0-13-release.md)。
- 存储初始化时仅清理已中断上传/发布 `.part` 文件；已提交资源不受影响。导入失败增加任务 ID/错误码日志，既有备份失败按任务编号定位。
- 修复首次同步就绪前设置、每日计划和练习写入入口的竞争，等待连接时保留输入和已展开答案；清理导入确认页面过时的“后续提供学习”提示。
- 修复同题再次练习时历史成功收据引发的旧会话跳转，同时保留刷新前待确认开始请求的收据恢复。
- CI 增加 HTTPS 发布栈、真实 PDF/双会话、ZIP 往返、重建持久化和异常恢复任务，上传结果 JSON/截图/脱敏日志。远端 CI 尚未推送执行，本地实际验证单独记录。

## 重现方式

在仓库根目录，准备 Docker Compose、Node、Playwright、Python、PowerShell 7 和 OpenSSL。仅使用本脚本生成的固定本地账号/回环端口/专用项目；**脚本会重置和删除该测试空间题库**。测试数据和私钥写入已忽略的 `output/p0-13`，禁止将该目录作为公网部署配置。

```powershell
./docs/fixtures/p0-13/setup-local.ps1
docker compose -p flashcards-p013-acceptance --env-file output/p0-13/.env -f deploy/compose.production.yml up --build -d --wait --wait-timeout 240
python tools/release-check.py --base-url https://localhost:8443 --http-url http://localhost:8089 --cafile output/p0-13/tls/fullchain.pem
# PLAYWRIGHT_MODULE 可指定已有 playwright 模块的绝对目录；缺省使用已安装的 playwright。
# 默认浏览器 msedge；Linux/CI 使用 PLAYWRIGHT_BROWSER_CHANNEL=chromium 并先安装 Chromium。
node docs/fixtures/p0-13/run-browser.cjs core
docker compose -p flashcards-p013-acceptance --env-file output/p0-13/.env -f deploy/compose.production.yml down
docker compose -p flashcards-p013-acceptance --env-file output/p0-13/.env -f deploy/compose.production.yml up -d --wait --wait-timeout 180
node docs/fixtures/p0-13/run-browser.cjs persistence
node docs/fixtures/p0-13/run-browser.cjs destructive
```

core 需要空测试卷；重复整轮时可先对**该专用测试项目**执行 `down -v`。core 依次复用逐章合并/拆分/排除、每日计划、评分/冲突、备份恢复脚本，两套首次学习断言间显式重置测试进度；结果不要求所有章正式导入。persistence 对比重建前后全部结构化行（按 ID 排序）、修订/代次，并实际读取原 PDF 和已导出的备份；destructive 最后才执行删除、旧设备离线评分拒绝、错误恢复和真实 PDF 重绘。

如需临时安装 Playwright，可装入忽略目录（本机可复用已安装模块）：

```powershell
npm.cmd install --prefix output/p0-13/runner --no-save --package-lock=false playwright@1.62.1
./output/p0-13/runner/node_modules/.bin/playwright.cmd install chromium
$env:PLAYWRIGHT_MODULE = (Resolve-Path output/p0-13/runner/node_modules/playwright).Path
$env:PLAYWRIGHT_BROWSER_CHANNEL = 'chromium'
```

## 验收边界

自动化手机上下文使用 320/360px 与不同学习时区；不将仿真记作实体设备。后续公网部署需填写设备/系统/浏览器与实际域名，完成同账号学习同步、导出恢复和旧设备重连。当前保留 P0-13“发布待验收”状态，不进入 P1，也不宣告第一版已发布。

## 本地实际验证

| 项目 | 结果 |
| --- | --- |
| 当前 PDF 重新提取与解析边界回归 | 10 项通过，222 页、158 主问题、13 章与冻结基线一致 |
| 前端全量测试 | 16 个文件、109 项通过 |
| 前端类型检查和生产构建 | 通过；验收使用 Docker 内生产构建 |
| 后端单元/契约 | 23 项通过 |
| PostgreSQL 18.3 集成与打包 | 68 项通过，V1～V10 迁移和构建通过 |
| HTTPS 发布栈 | 构建/启动/健康通过；证书主机名/信任、308、HSTS、MIME/缓存、静态 404、同源鉴权及请求编号通过 |
| 主流程和双会话 | core 通过：逐章调整、计划/范围、三档评分、撤销/重练、冲突、完整 ZIP 往返、旧代次拒绝 |
| 删除容器后保留卷重建 | persistence 通过：23 张业务表逐行、代次/修订、原 PDF 和备份下载一致 |
| 删除/重置与异常恢复 | destructive 三个脚本通过：过期预览拒绝、明确确认、旧离线评分保留核对、原 PDF 保留、PDF 加载失败重试、无效上传保留副本、未保存退出保护 |
| PDF.js 手机阅读 | 真实第 8/222 页实际绘制；原图 935×1210；360→1280→360px、200%→适屏均完成重绘，无页面溢出、ResizeObserver 循环或其他窗口错误 |

隔离测试库端口 `55443` 仅供随机 schema 的后端集成测试；发布栈项目 `flashcards-p013-acceptance` 不对外暴露数据库/后端，入口仅 `127.0.0.1:8089/8443`。浏览器为本机 Edge，上海/洛杉矶独立上下文；桌面 1280px 与手机 320/360px。

core 从 158 张解析草稿开始，合并 2 题（有效数 -1）、拆为 2 题（+1，保留历史草稿）、再排除 1 题（-1），确认 Java 基础 16 张，其余 12 章保持草稿。备份时共 159 行草稿（含排除/新增历史）、16 张正式卡片、11 条学习事件；与不可变原始解析报告的差异有调整记录。实际 ZIP `output/p0-13/roundtrip.zip` 为 3,593,793 字节，恢复后原日期/笔记/草稿/来源可用，旧浏览器离线笔记保留为待核对，旧请求 409。

证据保存于忽略目录 `output/p0-13/`：`core-result.json`、`persistence-result.json`、`destructive-result.json`、浏览器日志、真实 ZIP 和截图；后端完整日志为 `output/p013-backend-verify.log`。所有最终业务脚本报告运行时错误为 0；已目视核对手机完整答案与实际 PDF 渲染截图。历史脚本按钮/题库入口与空复习行为在本次 runner 中适配，未更改业务数量或弱化断言。未保存保护脚本根据当前设置值选择不同的新值，保证确实发生未保存编辑；此前串联脚本将目标保存为 0，不能再假定填写 0 会产生修改。正式 CI 另使用 Chromium，远端结果仍需实际推送后确认。

验收结束关闭本轮专用容器，保留发布栈测试卷、忽略目录证据和可再次导入的真实 ZIP；后端集成测试的独立容器已移除。重新启动方式见上文。测试卷最后执行了明确删除题库场景，因此题库为空、来源 PDF 仍在；若需要再次走 core，应仅对本轮专用项目重建空卷。

## 设计文档第 10 节的 12 条标准

| 标准 | 本次本地证据 | 发布边界 |
| --- | --- | --- |
| 1 PDF 导入与候选数量 | 10 项解析回归、真实上传/重复上传、调整原因与数量核对 | 通过 |
| 2 单章确认及其他章续检 | 合并/拆分/排除、16 张正式卡片、12 章草稿、独立会话续检 | 通过 |
| 3 完整跨页答案/追问/异常 | HashMap 全文尾部核对、带标记确认、完整阅读 | 通过 |
| 4 PDF 来源 | 真实来源绘制、重建后文件读取、手机视口核对 | 实体手机待签收 |
| 5 主动回忆/三档自评 | 默认隐藏、揭示后评分、三个评分/撤销/重练 | 通过 |
| 6 历史调度 | 规则单元/集成覆盖跨日、逾期、月末/年末；浏览器同日重练不升级 | 通过；跨日使用受控测试时钟，未伪称等待自然日 |
| 7 数量/范围/批量 | 目标 0、批量 1、暂停恢复、原日期和统计不变 | 通过 |
| 8 中断/重开保留 | 响应丢失后重载、会话继续、整个容器重建逐行核对 | 通过 |
| 9 薄弱内容与笔记再练习 | 状态筛选、笔记保留、同题新练习与有限重练 | 通过，筛选全组合由数据库/组件回归覆盖 |
| 10 手机电脑操作/可读性 | 320/360/1280px 阅读、触控与键盘焦点、来源 PDF | 实体手机待签收 |
| 11 同步/去重/失败提示 | 不同时区双上下文、丢响应、评分冲突及原收据、不重复计数 | 实体手机与公网网络待签收 |
| 12 完整备份恢复 | 真实 ZIP 下载/预检/确认恢复、原日期与旧队列隔离、重建后下载 | 本地通过；实体手机与公网往返待签收 |

本轮未执行公网升级/回退，也未验证实体手机。升级迁移/快照/回退已交付可操作手册，后续服务器部署时应先演练再签收，不能以文档步骤代替已执行证据。
