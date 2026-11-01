<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiError } from '../api/client'
import { overview, usageUsers, usageDetail, type Overview, type UsageUsers, type UsageDetail, type UsageDay } from '../api/dashboard'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore()
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
// Use Shanghai dates even when the browser is in a different timezone.
const from = ref(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(Date.parse(`${today}T00:00:00+08:00`) - 6 * 86400000)))
const to = ref(today), search = ref(''), sort = ref('recent'), page = ref(0)
const data = ref<Overview | null>(null), users = ref<UsageUsers | null>(null), detail = ref<UsageDetail | null>(null)
const loading = ref(false), error = ref('')
let alive = true, serial = 0, selection = 0
let applied = new URLSearchParams()
const time = (v: string | null) => v ? new Date(v).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '尚无记录'
const count = (v: number | null | undefined) => v == null ? '未采集' : v.toLocaleString('zh-CN')
const duration = (v: number | null | undefined) => v == null ? '未采集' : `${(v / 60).toFixed(1)} 分钟`
const bytes = (v: number | null | undefined) => v == null ? '—' : `${(v / 1048576).toFixed(1)} MiB`
const cards = computed(() => {
  const s = data.value?.summary ?? {}
  return [ ['用户总数', count(s.users)], ['启用 / 停用', `${count(s.enabled)} / ${count(s.disabled)}`], ['今日新增用户', count(s.today_new_users)], ['当前在线', count(s.online_users)], ['今日 / 近 7 日 / 近 30 日活跃', `${count(s.today_active)} / ${count(s.week_active)} / ${count(s.month_active)}`], ['区间活跃 / 学习人数', `${count(s.active_users)} / ${count(s.learning_users)}`], ['区间有效在线时长', duration(s.seconds)], ['区间新学 / 复习', `${count(s.new_count)} / ${count(s.review_count)}`], ['当前全站存储', bytes(s.storage_bytes)], ['当前失败导入任务', count(s.failed_imports)] ]
})
const trend = computed(() => detail.value?.days ?? data.value?.days ?? [])
const maxDuration = computed(() => Math.max(1, ...trend.value.map(d => d.seconds ?? 0)))
function failure(cause: unknown) {
  error.value = cause instanceof ApiError ? cause.message : '使用统计读取失败，请重试'
  if (cause instanceof ApiError && ['FORBIDDEN', 'UNAUTHENTICATED', 'ACCOUNT_CHANGED', 'PASSWORD_CHANGE_REQUIRED'].includes(cause.code)) void auth.refresh().catch(() => {})
}
async function load(reset = false) {
  if (reset) page.value = 0
  const ticket = ++serial; ++selection; detail.value = null; loading.value = true; error.value = ''
  const params = new URLSearchParams({ from: from.value, to: to.value })
  try {
    const [a, b] = await Promise.all([overview(params), usageUsers(new URLSearchParams({ ...Object.fromEntries(params), search: search.value, sort: sort.value, page: String(page.value), size: '10' }))])
    if (!alive || ticket !== serial) return
    data.value = a.data; users.value = b.data; applied = params
  } catch (cause) { if (alive && ticket === serial) { data.value = null; users.value = null; failure(cause) } }
  finally { if (alive && ticket === serial) loading.value = false }
}
async function select(id: string) {
  const ticket = ++selection; detail.value = null; error.value = ''
  try { const result = await usageDetail(id, applied); if (alive && ticket === selection) detail.value = result.data }
  catch (cause) { if (alive && ticket === selection) { data.value = null; users.value = null; failure(cause) } }
}
function refresh() { if (document.visibilityState === 'visible') void load() }
let timer: ReturnType<typeof setInterval>
onMounted(() => { void load(); document.addEventListener('visibilitychange', refresh); window.addEventListener('focus', refresh); timer = setInterval(refresh, 60_000) })
onUnmounted(() => { alive = false; ++serial; ++selection; clearInterval(timer); document.removeEventListener('visibilitychange', refresh); window.removeEventListener('focus', refresh) })
function learning(d: UsageDay) { return `${d.new_count} / ${d.review_count}` }
</script>
<template>
  <section class="dashboard" aria-labelledby="dashboard-title">
    <h2 id="dashboard-title">Dashboard 与使用汇总</h2>
    <p class="muted">统计时区 Asia/Shanghai。应用活跃、最近登录与有效评分分别统计；仅展示使用元数据。</p>
    <form class="filters" @submit.prevent="load(true)">
      <label>开始日期<input v-model="from" type="date" :max="to" :min="data?.retainedFrom" required /></label>
      <label>结束日期<input v-model="to" type="date" :min="from" :max="today" required /></label>
      <label>用户名或昵称<input v-model="search" maxlength="100" /></label>
      <label>用户排序<select v-model="sort"><option value="recent">最近活跃</option><option value="duration">区间使用时长</option></select></label>
      <button class="primary-button" :disabled="loading">查询 / 刷新统计</button>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="loading" role="status">正在读取统计…</p>
    <template v-if="data">
      <p class="metadata">已应用 {{ data.from }} 至 {{ data.to }}（含首尾日） · 更新 {{ time(data.updatedAt) }}<br />时长采集起点 {{ time(data.collectionStartedAt) }} · 每日汇总保留 12 个月</p>
      <p v-if="data.activityCoverage !== 'COLLECTED'" class="notice">{{ data.activityCoverage === 'UNCOLLECTED' ? '此区间在线时长尚未采集。' : '此区间部分时段尚未采集，活动与时长仅含采集起点之后的数据。' }}学习量依据现有有效评分记录。</p>
      <div class="metrics"><article v-for="card in cards" :key="card[0]"><p>{{ card[0] }}</p><strong>{{ card[1] }}</strong></article></div>
      <section class="box">
        <h3>逐用户使用汇总</h3><p class="muted">使用天数、新学、复习及知识库领取均为已应用区间；存储和在线状态为当前值。零使用账号同样列出。</p>
        <p v-if="!users?.items.length">暂无符合条件的用户。</p>
        <div class="user-grid"><article v-for="user in users?.items" :key="user.id" class="user">
          <h4>{{ user.nickname || user.username }} <small>{{ user.online ? '在线' : '离线' }}</small></h4><p>{{ user.username }} · {{ user.enabled ? '启用' : '停用' }}</p>
          <p>最近登录：{{ time(user.last_login_at) }}<br />最近活跃：{{ time(user.last_active_at) }}</p>
          <p>使用 {{ count(user.active_days) }} 天 · {{ duration(user.seconds) }}<br />新学 / 复习 {{ user.new_count }} / {{ user.review_count }}<br />领取 {{ user.claims }} 个知识库 · 存储 {{ bytes(user.storage_bytes) }}</p>
          <button class="secondary-button" :disabled="loading" @click="select(user.id)">查看 {{ user.username }} 每日汇总</button>
        </article></div>
        <div class="pagination"><button class="secondary-button" :disabled="!page || loading" @click="page--; load()">上一页</button><span>第 {{ page + 1 }} 页 · 共 {{ users?.total ?? 0 }} 人</span><button class="secondary-button" :disabled="loading || (page + 1) * 10 >= (users?.total ?? 0)" @click="page++; load()">下一页</button></div>
      </section>
      <section class="box"><h3>{{ detail ? `${detail.user.nickname || detail.user.username} 的每日汇总` : '全站每日趋势' }}</h3>
        <button v-if="detail" class="secondary-button" @click="++selection; detail = null">返回全站趋势</button>
        <p v-if="!trend.some(d => d.learning_users || d.active_users)" class="muted">此区间暂无活动或学习记录。采集前显示“未采集”，已采集但无使用显示 0。</p>
        <div class="trend-scroll" tabindex="0" aria-label="每日趋势，可滚动"><table><thead><tr><th>上海日期</th><th>活跃人数</th><th>学习人数</th><th>有效在线时长</th><th>新学 / 复习</th></tr></thead><tbody><tr v-for="day in trend" :key="day.day"><th scope="row">{{ day.day }}</th><td>{{ count(day.active_users) }}</td><td>{{ day.learning_users }}</td><td><span class="bar" :style="{ width: `${(day.seconds ?? 0) / maxDuration * 100}%` }"></span><span class="duration">{{ duration(day.seconds) }}</span></td><td>{{ learning(day) }}</td></tr></tbody></table></div>
      </section>
    </template>
    <p class="muted policy">采集口径：前台可见且最近 5 分钟有键盘、点击或滚动交互，每 30 秒上报活动时间。最近 90 秒收到合格心跳视为在线；首个心跳只建立起点，相邻间隔超过 60 秒不补算。跨标签页和设备取时间并集，后台挂页、长时间无交互阅读及断网不累计，不采集输入内容。原始活动区间保留 30 天。</p>
  </section>
</template>
<style scoped>
.dashboard { margin-top: 1.5rem; min-width: 0; } .filters,.pagination { display: flex; flex-wrap: wrap; align-items: end; gap: .8rem; margin: 1rem 0; }
.filters label { display: grid; gap: .4rem; flex: 1 1 150px; } input,select { width: 100%; min-height: 44px; border: 1px solid var(--border,#b8c7bc); border-radius: 8px; padding: .6rem; background: white; font: inherit; color: inherit; }
.metrics,.user-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(min(100%,230px),1fr)); gap: .8rem; }
.metrics article,.box,.user { min-width: 0; padding: 1rem; border: 1px solid var(--border,#d8e0d9); border-radius: 12px; background: var(--surface,#fff); }
.metrics strong { font-size: 1.4rem; color: #355c4a; } .metrics p { margin-top: 0; } .box { margin-top: 1.2rem; } h4 { margin: 0; overflow-wrap: anywhere; } small { font-size: .8rem; font-weight: normal; }
.metadata,.policy { font-size: .85rem; line-height: 1.7; overflow-wrap: anywhere; } .trend-scroll { overflow: auto; max-height: 520px; margin-top: 1rem; } table { border-collapse: collapse; width: 100%; min-width: 600px; font-size: .9rem; } th,td { padding: .7rem; border-bottom: 1px solid var(--border,#ddd); text-align: left; white-space: nowrap; } td { position: relative; } .bar { position: absolute; left: 0; top: 15%; height: 70%; background: #e2eee5; border-radius: 4px; } .duration { position: relative; } .pagination { align-items: center; }
</style>
