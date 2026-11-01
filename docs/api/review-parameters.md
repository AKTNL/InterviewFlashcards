# P1-04：个人复习参数与效果观察

所有接口要求登录；写请求要求 CSRF。日期采用服务端学习时区 `Asia/Shanghai`，时间戳为 UTC。只处理当前账号，不接受客户端指定 userId。

## 读取配置、历史和观察结果

`GET /api/review-parameters?days=90`，days 只接受 30、90、180，默认 90。事务使用 REPEATABLE READ，不增加修订号。

返回：

- `current`：`version/algorithmVersion/intervals/reason/createdAt`。未设置个人参数时 version=0，使用部署默认参数，createdAt=null。
- `history`：最近 20 次个人变更，按 version 倒序；服务端和完整 ZIP 保留全部历史。
- `from/to/learningTimezone/generatedAt`：含首尾自然日的观察范围及生成时间。
- `activity`：按评分所用算法版本统计有效 RATE/CORRECT 的 `assessments/firstAssessments/replays`；首次指 before_state 为 JSON null，同日重练单列。不是学习题数或效果样本。
- `outcomes`：按安排间隔的 `algorithmVersion` 与 `parameters` 快照分组，含 `observations/cards/forgot/vague/remembered/onTime/late/averageLateDays/averagePlannedDays`。

效果样本为同一卡片相邻有效安排之后，下一次跨日、已到期的 RATE/CORRECT。前一个安排可以在范围开始前，观察结果必须在范围内；按安排间隔的版本归因，不按结果事件的新版本归因。相同卡片多次到期可以贡献多个样本，cards 为各组不同卡片数。首次学习、提前复习、同日重练本身不作效果样本，RELEARN 中断观察链。重练若只保留 dueDate，不抢占旧安排的归因；若确实缩短 dueDate，则使用产生新安排的快照。

`onTime` 为实际学习日等于原 dueDate；`late` 为晚于原 dueDate。平均逾期天数包含准时样本的 0，平均安排天数为 dueDate 减安排日，均保留一位小数。自评记住比例由 remembered/observations 计算，空样本返回空数组。没有声称因果改善、客观掌握率或自动调参；样本受题目难度、逾期和间隔影响。

撤销和被更正事件按统一有效事件定义排除；暂停章节仍参与，删除/重置/恢复后基于保留历史重算。旧事件没有参数快照时返回 `{}`，界面标记“历史参数未记录”；不会用当前配置倒填历史。同一版本名的不同证据快照分开展示。

## 保存新版本

`POST /api/review-parameters`

```json
{
  "operationId": "UUID",
  "dataEpoch": "UUID",
  "expectedVersion": 0,
  "intervals": [1, 2, 5, 10, 20],
  "reason": "长间隔较容易遗忘，先缩短后观察"
}
```

五个整数，各 1～365 天，非递减；reason 必填、最多 500 字符，保存时去除首尾空白。间隔必须不同于当前值。版本由服务端生成：version 单调增加，algorithmVersion 为 `personal-UUID`；不允许客户端指定或覆盖旧版本。恢复过去的间隔同样生成新版本并保存原因。三档评分规则、阶段变化和同日重练限制保持原规则，仅个人“记住了”五阶段天数可配。

返回 `current` 和标准 `mutation`。沿用用户锁、代次、幂等摘要/收据和修订号事务，同一操作重复返回同一新版本；同 operationId 改内容为 OPERATION_ID_REUSED。并发与旧版本保存返回 VERSION_CONFLICT（details.current）；恢复后的旧请求返回 EPOCH_EXPIRED。校验失败为 INVALID_REQUEST。

参数变更不更新 review_state、不改会话、不重算历史 dueDate；后续评分在用户写锁内读取当前参数，将完整版本、间隔、原因和创建时间快照写入 `review_event.algorithm_parameters`。撤销复制原事件版本/快照并恢复原状态；冲突更正使用原评分参数。旧 rules-v1 缺快照时允许按首版固定参数更正，其他未知旧版本拒绝候选更正，需接受当前结果后主动重新练习。

页面使用在线保存，不进入离线评分队列；响应丢失时在当前页面保留原操作并可幂等确认，重新读取前须确认放弃输入，并核对历史是否已生效。修订刷新保留本地编辑，版本冲突不自动覆盖。

## 同步和备份

V18 新增 `review_policy` 及事件 JSON 快照；同步快照包含个人参数历史。个人 ZIP 格式继续为 1，新增表及字段均导出并跟随登录用户恢复；旧格式 1 缺表时补空数组，旧事件缺字段补 `{}`。预检验证个人参数的范围、顺序与事件快照结构；完整恢复原子替换参数、历史和日程，保留原 dueDate。题库内容复制不带学习参数；删除/重置单个题库不重置账号参数。
