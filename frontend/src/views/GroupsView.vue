<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { groupsApi, type GroupList, type GroupDetail } from '../api/groups'

const list = ref<GroupList>({ groups: [], incoming: [] }), detail = ref<GroupDetail>()
const selected = ref(''), name = ref(''), recipient = ref(''), hours = ref(24)
const createConfirmed = ref(false), joinConfirmed = ref<Record<string, boolean>>({}), consentConfirmed = ref(false)
const busy = ref(false), loading = ref(false), error = ref(''), message = ref('')
let generation = 0, disposed = false, timer: ReturnType<typeof setInterval> | undefined
let createCommand: { id: string; name: string; confirmed: boolean } | undefined
let inviteCommand: { id: string; recipient: string; expiresInHours: number } | undefined
watch(name, () => { createCommand = undefined; createConfirmed.value = false })
watch([selected, recipient, hours], () => { inviteCommand = undefined })
watch(selected, () => { consentConfirmed.value = false; void load() })
function failure(cause: unknown) { error.value = cause instanceof Error ? cause.message : '操作失败，请刷新后重试' }
async function load() {
  const current = ++generation
  if (detail.value?.id !== selected.value) detail.value = undefined
  else if (detail.value) detail.value.members = []
  loading.value = true
  try {
    const result = await groupsApi.list()
    if (current !== generation || disposed) return
    list.value = result
    if (selected.value && result.groups.some(g => g.id === selected.value)) {
      const group = await groupsApi.detail(selected.value)
      if (current === generation && !disposed) detail.value = group
    } else if (selected.value) selected.value = ''
  } catch (cause) { if (current === generation && !disposed) { detail.value = undefined; list.value = { groups: [], incoming: [] }; failure(cause) } }
  finally { if (current === generation) loading.value = false }
}
async function act(work: () => Promise<unknown>, success: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''; generation++; detail.value = undefined; consentConfirmed.value = false
  try { await work(); if (!disposed) message.value = success }
  catch (cause) { if (!disposed) failure(cause) }
  finally { busy.value = false; if (!disposed) await load() }
}
async function create() {
  if (!createConfirmed.value || !name.value.trim()) return
  createCommand ??= { id: crypto.randomUUID(), name: name.value.trim(), confirmed: true }
  const command = createCommand
  await act(async () => {
    const result = await groupsApi.create(command); createCommand = undefined
    selected.value = result.id; createConfirmed.value = false
  }, '小组已创建；学习摘要默认不分享。')
}
async function invite() {
  if (!detail.value?.owner || !recipient.value.trim() || hours.value < 1 || hours.value > 168) return
  inviteCommand ??= { id: crypto.randomUUID(), recipient: recipient.value.trim(), expiresInHours: hours.value }
  const id = detail.value.id, command = inviteCommand
  await act(async () => { await groupsApi.invite(id, command); inviteCommand = undefined }, '邀请已发出，朋友需主动确认加入。')
}
async function consent() {
  const g = detail.value
  if (!g || (!g.sharing && !consentConfirmed.value)) return
  await act(() => groupsApi.consent(g.id, g.version, !g.sharing), g.sharing ? '已停止分享；其他成员不能再查询你的学习摘要。' : '已授权本组成员查看学习摘要。')
}
async function leave() {
  const g = detail.value
  if (!g || !window.confirm(g.owner ? '解散小组并退出？所有成员的摘要和邀请将停止访问。' : '退出小组并停止分享摘要？重新加入需要新的邀请。')) return
  await act(() => groupsApi.leave(g.id), g.owner ? '小组已解散。' : '已退出小组并停止分享。')
}
function activate() {
  generation++; detail.value = undefined; consentConfirmed.value = false
  if (document.visibilityState === 'visible' && !busy.value) void load()
}
onMounted(() => {
  void load()
  timer = setInterval(() => { if (document.visibilityState === 'visible' && !busy.value && !loading.value) void load() }, 15_000)
  window.addEventListener('focus', activate); window.addEventListener('online', activate)
  document.addEventListener('visibilitychange', activate)
})
onUnmounted(() => {
  disposed = true; generation++; clearInterval(timer)
  window.removeEventListener('focus', activate); window.removeEventListener('online', activate)
  document.removeEventListener('visibilitychange', activate)
})
</script>
<template>
  <section class="sources-intro"><p class="eyebrow">TOGETHER · 自愿打卡</p><h1>学习小组</h1><p>和朋友互相督促。加入后组内可见你的昵称（未设置时显示用户名），学习摘要默认不分享。</p>
    <p>自愿授权后，仅展示今天是否学习、今日新学/复习数量和近 7 个学习日内的学习天数，并标注学习日与时区。笔记、具体自评、薄弱题和完整历史保持私有。</p>
    <p>可以随时停止分享或退出；注册、领取题库及恢复备份都不会自动加入或重新开启授权。</p>
  </section>
  <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="message" role="status" class="notice">{{ message }}</p>
  <button class="secondary-button" :disabled="busy || loading" @click="load">刷新小组与邀请</button><p class="form-note">摘要来自已同步的有效评分，每 15 秒及返回页面时刷新。同日重练和请求重试不重复计数。</p>
  <section class="chapter-review learning-settings" aria-label="创建小组"><h2>创建小组</h2>
    <label>小组名称<input v-model="name" maxlength="80" :disabled="busy" /></label>
    <label><input v-model="createConfirmed" type="checkbox" :disabled="busy" />我了解昵称展示范围，创建并加入小组；暂不分享学习摘要。</label>
    <button class="primary-button" :disabled="busy || !name.trim() || !createConfirmed" @click="create">创建并加入</button>
    <p class="form-note">每人最多创建 5 个有效小组；每组最多 50 位成员、50 个有效待接受邀请。组主退出会解散小组。</p>
  </section>
  <section class="chapter-review" aria-label="小组邀请"><h2>收到的小组邀请</h2><p v-if="!list.incoming.length">暂无有效邀请。</p>
    <article v-for="i in list.incoming" :key="i.id" class="confirm-panel"><h3>{{ i.name }}</h3><p>来自 {{ i.inviter }} · 有效期至 {{ new Date(i.expiresAt).toLocaleString() }}</p>
      <p>加入后本组成员可见你的昵称；摘要默认关闭，可在加入后单独授权。退出后原邀请不能再次加入。</p>
      <label><input v-model="joinConfirmed[i.id]" type="checkbox" :disabled="busy" />我了解展示范围，自愿加入此小组。</label>
      <button class="primary-button" :disabled="busy || !joinConfirmed[i.id]" @click="act(() => groupsApi.join(i.groupId, i.id), '已加入，学习摘要默认不分享。')">确认加入</button>
    </article>
  </section>
  <section class="chapter-review learning-settings" aria-label="我的小组"><h2>我的小组</h2><p v-if="!list.groups.length">尚未加入小组。</p>
    <label v-if="list.groups.length">选择小组<select v-model="selected" :disabled="busy"><option value="">请选择</option><option v-for="g in list.groups" :key="g.id" :value="g.id">{{ g.name }}{{ g.owner ? '（组主）' : '' }}</option></select></label>
    <p v-if="loading" role="status">正在核对成员权限与摘要…</p>
    <template v-if="detail"><h3>{{ detail.name }}</h3>
      <section class="confirm-panel" aria-label="摘要授权"><h4>我的学习摘要：{{ detail.sharing ? '已分享' : '未分享' }}</h4>
        <label v-if="!detail.sharing"><input v-model="consentConfirmed" type="checkbox" :disabled="busy" />允许本组成员查看今天是否学习、今日新学/复习数量及近一周学习天数。</label>
        <button class="secondary-button" :disabled="busy || (!detail.sharing && !consentConfirmed)" @click="consent">{{ detail.sharing ? '停止分享摘要' : '授权分享摘要' }}</button>
      </section>
      <div class="group-members"><article v-for="(m, index) in detail.members" :key="index" class="confirm-panel"><h4>{{ m.nickname }}{{ m.self ? '（我）' : '' }}</h4>
        <template v-if="m.summary"><p>{{ m.summary.learningDate }} · {{ m.summary.learningZone }}</p><p>{{ m.summary.studiedToday ? '今天已学习' : '今天尚无有效学习记录' }}</p><p>今日新学 {{ m.summary.todayNew }} · 复习 {{ m.summary.todayReview }}</p><p>近 7 个学习日内学习 {{ m.summary.weekLearningDays }} 天</p></template>
        <p v-else>未分享学习摘要</p>
      </article></div>
      <section v-if="detail.owner" class="confirm-panel learning-settings" aria-label="邀请成员"><h4>邀请朋友</h4>
        <label>朋友的登录用户名<input v-model="recipient" maxlength="100" :disabled="busy" /></label><label>有效期（小时）<input v-model.number="hours" type="number" min="1" max="168" :disabled="busy" /></label>
        <button class="primary-button" :disabled="busy || !recipient.trim() || hours < 1 || hours > 168" @click="invite">发送小组邀请</button>
        <article v-for="i in detail.invites" :key="i.id"><p>{{ i.recipient }} · {{ i.accepted ? '已接受' : i.revoked ? '已撤销' : i.expired ? '已过期' : '等待接受' }} · {{ new Date(i.expiresAt).toLocaleString() }}</p><button v-if="!i.revoked && !i.accepted" class="secondary-button" :disabled="busy" @click="act(() => groupsApi.revoke(selected, i.id), '邀请已撤销。')">撤销邀请</button></article>
      </section>
      <button class="secondary-button" :disabled="busy" @click="leave">{{ detail.owner ? '解散小组并退出' : '退出小组' }}</button>
    </template>
  </section>
</template>

<style scoped>
.group-members { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 230px), 1fr)); gap: 1rem; }
.confirm-panel { overflow-wrap: anywhere; }
</style>
