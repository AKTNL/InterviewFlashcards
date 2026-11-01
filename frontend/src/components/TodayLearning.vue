<script setup lang="ts">
import { onSyncRefresh } from '../sync/refresh'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { learningApi, learningModes, type TodayLearning } from '../api/learning'
import { useSyncStore } from '../stores/sync'

const router = useRouter(), sync = useSyncStore()
const today = ref<TodayLearning>(), error = ref(''), loading = ref(false), busy = ref(false)
const pending = computed(() => sync.items.some(i => ['session.daily', 'preferences.save'].includes(i.kind) && i.status !== 'synced'))
const seen = new Set(sync.items.filter(i => i.status === 'synced').map(i => i.operationId))
let generation = 0
let requested: { mode: string; after: number } | undefined
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  try { const value = await learningApi.today(); if (current === generation) today.value = value }
  catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '读取今日计划失败' }
  finally { if (current === generation) loading.value = false }
}
async function start(mode: string) {
  if (pending.value || busy.value || !today.value?.preferences.saved || !sync.state) return
  busy.value = true; error.value = ''
  requested = { mode, after: Date.now() }
  try { await sync.enqueue({ kind: 'session.daily', name: learningModes[mode]!, payload: { mode } }) }
  catch (cause) { requested = undefined; error.value = cause instanceof Error ? cause.message : '创建学习失败' }
  finally { busy.value = false }
}
watch(() => sync.items.filter(i => i.status === 'synced').map(i => i.operationId).join(','), async () => {
  let changed = false
  for (const i of sync.items) {
    if (i.status !== 'synced' || seen.has(i.operationId)) continue
    seen.add(i.operationId); changed = true
    if (i.kind === 'session.daily' && i.result?.session && requested && requested.mode === i.payload?.mode && i.createdAt >= requested.after) {
      requested = undefined; await router.push(`/practice/${i.result.session.id}`); return
    }
  }
  if (changed) await load()
})
function activate() { if (document.visibilityState === 'visible') void load() }
onMounted(() => { void load(); window.addEventListener('focus', activate); document.addEventListener('visibilitychange', activate) })
onUnmounted(() => { generation++; window.removeEventListener('focus', activate); document.removeEventListener('visibilitychange', activate) })
onSyncRefresh(load)
</script>
<template>
  <section class="today-panel" aria-labelledby="today-title">
    <div class="list-heading"><div><p class="eyebrow">TODAY · 今日学习</p><h1 id="today-title">今天，从一次回忆开始。</h1></div><RouterLink to="/settings">学习设置</RouterLink></div>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <p v-if="loading" role="status">正在读取今日计划…</p>
    <template v-if="today">
      <p>学习日 {{ today.learningDate }} · {{ today.learningTimezone }}</p>
      <div class="today-metrics">
        <div><strong>{{ today.dueTotal }}</strong><span>范围内全部到期</span></div>
        <div><strong>{{ today.summary.todayNew }} / {{ today.preferences.dailyNewCount }}</strong><span>今日新学 / 目标</span></div>
        <div><strong>{{ today.summary.todayReview }}</strong><span>今日复习（不同卡片）</span></div>
        <div><strong>{{ today.summary.learned }}</strong><span>总已学</span></div>
      </div>
      <p>今日剩余新题额度 {{ today.remainingNew }}，范围内未学 {{ today.availableNew }}。每轮最多复习 {{ today.preferences.reviewBatchSize }} 题，默认到期题优先。</p>
      <p v-if="today.preferences.referenceMinutes">每日参考 {{ today.preferences.referenceMinutes }} 分钟，按自己的节奏学习。</p>
      <p v-if="!today.preferences.saved" class="notice">先到学习设置选择章节并确认目标。建议每天新题 5 道、每轮复习 20 道，可自行修改。</p>
      <p v-else-if="!today.preferences.chapterIds.length" class="notice">尚未选择学习章节，请在学习设置中选择已确认导入的章节。</p>
      <p v-else-if="!today.chapters.some(c => c.selected && c.total > 0)" class="notice">当前学习范围内没有卡片。<RouterLink to="/settings">选择其他章节</RouterLink>，或<RouterLink to="/sources">继续导入检查</RouterLink>。</p>
      <p v-if="!today.dueTotal" class="notice">范围内没有到期题，可以先学新题，或<RouterLink to="/cards">到题库自主练习</RouterLink>。</p>
      <p v-if="!today.dueTotal && (!today.remainingNew || !today.availableNew)" class="notice">今天暂无新的计划条目。可以调整设置，或去题库自主练习薄弱题。</p>
      <div class="sync-actions"><button v-for="mode in ['MIXED', 'REVIEW', 'NEW']" :key="mode" class="primary-button" :disabled="!sync.state || !today.preferences.saved || busy || pending || loading || (mode === 'REVIEW' ? !today.dueTotal : mode === 'NEW' ? !today.remainingNew || !today.availableNew : !today.dueTotal && (!today.remainingNew || !today.availableNew))" @click="start(mode)">{{ learningModes[mode] }}</button></div>
      <p v-if="pending" role="status">操作已保留，等待服务端确认；可在“保存与同步”查看进度。</p>
      <section v-if="today.continuing.length" aria-label="继续学习"><h2>接着上次继续</h2><ul class="continue-list"><li v-for="s in today.continuing" :key="s.id"><RouterLink :to="`/practice/${s.id}`">继续{{ learningModes[s.mode] }} · 剩余 {{ s.remaining }} 项</RouterLink></li></ul></section>
      <section aria-label="章节学习情况"><h2>章节学习情况</h2><p v-if="!today.chapters.length">还没有正式章节，请先上传资料并逐章检查确认。</p><ul class="chapter-stats"><li v-for="ch in today.chapters" :key="ch.id"><strong>{{ ch.moduleName }} / {{ ch.name }}</strong><span>{{ ch.selected ? '学习中' : '已暂停' }} · 已学 {{ ch.learned }}/{{ ch.total }} · 到期 {{ ch.due }} · 薄弱 {{ ch.weak }}</span></li></ul></section>
    </template>
    <div class="sync-actions"><button class="secondary-button" :disabled="loading || busy" @click="load">刷新今日计划</button><RouterLink to="/cards?filter=WEAK">练习薄弱题</RouterLink><RouterLink to="/sources">我的资料</RouterLink></div>
  </section>
</template>
