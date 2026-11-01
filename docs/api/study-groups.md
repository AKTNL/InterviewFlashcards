# 学习小组与自愿摘要 API（P1-10）

所有接口要求登录、同源会话和写入 CSRF，沿用 `X-Account-ID` 身份校验、统一响应及错误格式。响应使用 `Cache-Control: no-store`。小组并不共享题库；题库内容副本继续使用 P1-09。

## 接口

| 方法与路径 | 请求 / 响应 |
| --- | --- |
| `GET /api/groups` | `groups: [{id,name,owner}]` 为本人当前有效成员资格；`incoming: [{id,groupId,name,inviter,expiresAt}]` 只含绑定本账号的有效待接受邀请，不含成员或摘要 |
| `POST /api/groups` | `{id: UUID,name: 1～80 字符,confirmed:true}`；返回 `{id}`。明确接受昵称展示范围，创建者加入但不分享摘要 |
| `GET /api/groups/{groupId}` | 仅有效成员可读，返回 `{id,name,owner,sharing,version,members,invites?}`。`invites` 仅组主可见，包含其主动填写的接收用户名、状态、到期时间；最多显示最近 100 条 |
| `POST /api/groups/{groupId}/invites` | 仅组主，`{id: UUID,recipient: 已注册登录用户名,expiresInHours:1～168}`；返回 `{id}`。收件用户名去首尾空格、转小写；无匿名链接 |
| `POST /api/groups/{groupId}/invites/{inviteId}/revoke` | 仅组主，空对象；返回 `true`。撤销阻止后续加入，已加入成员保持资格 |
| `POST /api/groups/{groupId}/join/{inviteId}` | 仅邀请绑定账号，`{confirmed:true}`；返回 `true`。新加入/重新加入均默认关闭摘要分享 |
| `POST /api/groups/{groupId}/consent` | 仅本人有效成员，`{expectedVersion:非负整数,sharing:boolean}`；返回 `{sharing,version}`。每次授权或撤回递增版本；旧版本返回 `VERSION_CONFLICT`，需重新读取和确认 |
| `POST /api/groups/{groupId}/leave` | 空对象；返回 `true`。普通成员退出并关闭授权，撤销其在本组的所有旧邀请；组主退出会解散小组、关闭全部授权并撤销全部邀请 |

`members` 的每项仅包含 `{nickname,self,sharing,summary?}`，昵称未设置时使用用户名；没有内部用户 ID。未授权成员完全不返回 `summary` 字段，而不是返回零值来暗示其未学习。加入前页面明确说明昵称向本组成员展示；摘要另行勾选授权，可以逐组分别控制。

授权后的 `summary` 仅包含：

```json
{
  "learningDate": "2026-11-01",
  "learningZone": "Asia/Shanghai",
  "studiedToday": true,
  "todayNew": 2,
  "todayReview": 3,
  "weekLearningDays": 4
}
```

复用 `LearningService.summary`，个人 `GET /api/review-events/summary` 同步增加 `weekLearningDays`。仅统计没有被撤销/替代的 `RATE/CORRECT`；每日新学按卡片首次有效学习日计数，复习按当日已学卡片去重，近一周按 `[today-6,today]` 有有效评分的不同学习日计数。`studiedToday` 等于当天新学加复习是否大于零。同日重练、幂等重试、评分候选、更正或撤销均遵守原有效事件口径。待同步操作不计入。

当前产品所有账号统一使用服务端学习时区 `Asia/Shanghai`，设备时区不改变学习日；每份摘要明确返回日期和时区，不以浏览器日期比较。独立用户学习时区尚未开放；将来开放时必须连同原学习统计口径一起演进，不能仅修改小组展示时区。摘要不含笔记、卡片、具体评分、薄弱题、完整历史、题库或来源文件。

## 重试、并发与权限

小组和授权是在线社会关系操作，独立于个人学习 `dataEpoch/revision` 和 IndexedDB 离线队列。创建/邀请的客户端 UUID 在同次网络重试时保持不变；同 UUID 不产生新小组或新邀请，不延长有效期；已关闭的小组不因重试重开。同 UUID 不能被另一账号或另一组接管。

接受邀请可重复确认已有有效成员资格，但不会重新开启关闭的摘要授权。退出撤销其旧邀请；再次加入必须由组主发新邀请，成员版本继续递增。授权写入失败或响应丢失后读取当前状态，用户再决定下一步，不自动换版本重放。

所有成员、邀请与授权变更先锁定该组行；摘要读取持有同组共享锁直到权限检查和统计完成。加入与撤销、最后名额与并发加入、撤回与摘要读取均有事务先后顺序。撤回/退出事务提交后发起的新查询不能再得到原授权摘要；不能收回此前已传输或被截屏的信息。服务端不保存摘要快照、收据或成员历史接口。

页面每 15 秒、回到前台、窗口获得焦点及重新联网时重新鉴权；请求失败、切换小组或隐藏页面先移除旧摘要，过期异步响应不能恢复它。其他成员已打开页面的显示在下次刷新时移除，服务端权限在事务提交时生效。

每人最多创建 5 个未解散小组，每组最多 50 位有效成员和 50 条有效待接受邀请，服务端执行限制。错误包括 `GROUP_LIMITED`、`GROUP_INVITE_UNAVAILABLE`、`SHARE_RECIPIENT_INVALID`（接收账号不可用，沿用既有错误码）、`VERSION_CONFLICT`。无成员资格、非组主操作或猜测别人的邀请 ID 返回 `NOT_FOUND`；无登录 / 无 CSRF 按已有契约拒绝。

## 迁移与恢复

V13 新增 `study_group`、`study_group_member`、`study_group_invite`。三表不进入个人 ZIP 白名单，也不在恢复时替换。因此恢复不能带回已退出资格、开启已撤回的摘要分享，或向另一账号复制关系；当前仍有效的关系保持当前授权状态。注册和题库领取也不操作这些表。

没有排行榜、聊天、催学通知、组内共同编辑、组主转让或强制加入。组主离开以明确确认的“解散小组并退出”处理。
