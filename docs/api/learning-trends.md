# P1-03：个人学习趋势

`GET /api/learning-trends?weeks=8&offset=0&limit=20`，要求登录，仅查询会话所属用户，不接受目标用户 ID。沿用标准 `data/meta` 信封、账号校验与错误响应。

## 参数和范围

| 参数 | 默认 | 约束 |
| --- | --- | --- |
| weeks | 8 | 整数 1～52，包含本周 |
| offset | 0 | 非负整数，仅用于遗忘题分页 |
| limit | 20 | 整数 1～100，仅用于遗忘题分页 |

非法类型、溢出或越界返回 400；未登录返回 401。前端提供 4/8/12/52 周选择，每页 20 题。区间以服务端学习时区 `Asia/Shanghai` 的周一开始，本周截止当前学习日，不包括未来日期。空周补零，跨年无需按周编号拼接。

## 返回字段

| 字段 | 含义 |
| --- | --- |
| learningDate / learningTimezone | 同一请求开始时的服务端学习日和时区 |
| from / to | 区间第一周周一 / 当前学习日，均包含 |
| generatedAt | 查询开始时间，UTC ISO 时间戳；界面按学习时区展示 |
| weeks[] | 按 weekStart 升序排列的周记录 |
| weeks[].weekStart / weekEnd | 周一 / 周日，本周 weekEnd 截止 to |
| weeks[].learningDays | 此周至少一次有效评分的不同学习日数 |
| weeks[].newCount | 首次有效学习日位于此周的不同卡片数 |
| weeks[].reviewCount | 此周有效复习的“卡片 × 学习日”数量；同卡跨日累计题次 |
| weeks[].assessments / forgot / vague / remembered | 有效自评总次数 / 不会 / 模糊 / 记住了次数，包含同日重练 |
| chapters[] | 全部当前 IMPORTED 章节，按题库及目录稳定顺序；含暂停和零题章节 |
| chapters[].id / name / moduleName / deckName / selected / total | 当前章节归属、当前学习范围和当前卡片总数 |
| chapters[].learnedBefore / learned | 区间开始前 / 截至当前学习日，有有效评分的卡片数 |
| chapters[].weakBefore / weak | 两个时点最近有效自评为不会或模糊的卡片数 |
| chapters[].forgot / vague / remembered | 截至当前学习日每张已学卡片的最近有效自评分布，每卡至多计一次 |
| chapters[].newCount / reviewCount / assessments | 此章节区间内新学题数、按日去重复习题次和有效自评次数 |
| forgotten.items[] | 当前复习状态中 lapseCount > 0 的卡片，返回 id/question/chapterName/moduleName/deckName/lapseCount/latestRating/dueDate |
| forgotten.total / offset / limit | 遗忘题总量和分页参数，超出末页时 items 为空，total 仍正确 |

## 有效事件口径

与首页摘要共用 `EffectiveReviews.CTE`：仅 RATE/CORRECT，排除存在 `undo_of` 指向它的原事件；UNDO/RELEARN 不算自评。被更正的旧评分被替代，CORRECT 本身仍计一次。首次学习日从该用户全部有效历史计算，然后才过滤区间；区间前已学的卡片不能重新算成新学。

每个首次学习日的卡片只计一次新学，该日后续重练既不增加新学，也不计复习；以后每个学习日最多计一次复习。跨日复习题次和不同卡片总量含义不同。自评计数包含重练，只表示自评结果，不能作为掌握率。未确认的本地操作、浏览、提示和展开答案均不计数。

章节基线和当前值各取截止时点最后一个有效事件（按 event_order），用于比较已学及最近自评；不会伪造历史题库内容快照。改名、删除、重置与恢复后以当前内容及剩余有效历史重算，不保留独立累计计数器。

遗忘排序采用当前 `review_state.lapse_count`，与卡片详情、复习算法一致：首次或跨日 FORGOT 增加一次，同日重练不增加；撤销/更正还原后的状态即时反映。排序范围是全部历史，与 weeks 无关；按次数降序、dueDate 升序、稳定卡片 ID 升序，包含暂停章节，最近已记住但有历史遗忘的题也保留。

整个响应在一个只读 REPEATABLE_READ 数据库快照中读取。查询不改变 revision/epoch、学习状态、会话或 dueDate，不产生收据。前端在修订/学习日/代次变化、重新激活及手动刷新时重读，失败清除旧结果并支持重试；切换范围重置遗忘分页，旧请求不能覆盖新范围。

没有数据库迁移或新增持久化字段；个人 ZIP 格式不变，恢复原有 review_event/review_state 后自动重算。此接口仅为个人空间，不改变后台统计或小组摘要授权。
