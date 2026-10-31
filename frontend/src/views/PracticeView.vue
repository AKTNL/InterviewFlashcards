<script setup lang="ts">
import { onSyncRefresh } from '../sync/refresh'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { cardsApi, ratings, type CardDetail } from '../api/cards'
import { learningApi, type LearningSession } from '../api/learning'
import { useSyncStore } from '../stores/sync'
import CardAnswer from '../components/CardAnswer.vue'

const route = useRoute(), router = useRouter(), sync = useSyncStore()
const session = ref<LearningSession>(), card = ref<CardDetail>(), answer = ref(false), hint = ref(false)
const error = ref(''), message = ref(''), busy = ref(false), loading = ref(false)
const cardIds = computed(() => String(route.query.cards ?? '').split(',').filter(Boolean))
const selected = computed(() => new Set(cardIds.value).size === cardIds.value.length && cardIds.value.length > 0 && cardIds.value.length <= 100)
const pending = computed(() => sync.items.some(i => i.status !== 'synced' && (i.kind === 'session.start' && !route.params.id || i.kind === 'review.write' && i.payload?.sessionId === route.params.id)))
const needsAttention = computed(() => sync.items.some(i => ['failed', 'needs-attention'].includes(i.status) && (i.kind === 'session.start' && !route.params.id || i.kind === 'review.write' && i.payload?.sessionId === route.params.id)))
const blocked = computed(() => busy.value || pending.value || loading.value || !session.value || session.value.expired || session.value.current?.currentVersion !== session.value.current?.expectedVersion)
const early = computed(() => session.value?.review && !session.value.expired && !session.value.sameDayReplay && session.value.review.dueDate > session.value.today)
const seen = new Set(sync.items.filter(i => i.status === 'synced').map(i => i.operationId))
let generation = 0
async function apply(value: LearningSession) {
  const current = ++generation
  if (session.value?.current?.id !== value.current?.id) { answer.value = false; hint.value = false; card.value = undefined }
  session.value = value
  if (value.status === 'INVALIDATED') message.value = ''
  if (!value.current) { card.value = undefined; return }
  loading.value = true
  try { const detail = await cardsApi.detail(value.current.cardId); if (generation === current) card.value = detail }
  catch (cause) { if (generation === current) error.value = cause instanceof Error ? cause.message : '读取题目失败' }
  finally { if (generation === current) loading.value = false }
}
async function load() {
  const invalidated = sync.state?.data?.learning_session?.some(s => s.id === route.params.id && s.status === 'INVALIDATED')
  if (!route.params.id || busy.value || pending.value && !invalidated) return
  error.value = ''; loading.value = true
  const id = String(route.params.id), current = ++generation
  try { const value = await learningApi.session(id); if (current === generation) await apply(value) }
  catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '读取练习失败' }
  finally { if (String(route.params.id) === id) loading.value = false }
}
async function start() {
  if (busy.value || pending.value || !selected.value) return
  busy.value = true; error.value = ''
  try { await sync.enqueue({ kind: 'session.start', name: `主动回忆 ${cardIds.value.length} 题`, payload: { cardIds: cardIds.value } }) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '创建练习失败' }
  finally { busy.value = false }
}
async function write(action: string, rating?: string) {
  const s = session.value, item = s?.current
  if (!s || blocked.value || action !== 'UNDO' && !item || action === 'RATE' && !answer.value) return
  if (action === 'RELEARN' && !window.confirm(`确认重新学习“${card.value?.question}”？当前复习日期：${s.review?.dueDate ?? '未学'}；执行后设为今日 ${s.today}，重新进入巩固。保留历史和首次学习信息，不重复计为新题。`)) return
  const confirmEarly = action === 'RATE' && !!early.value
  if (confirmEarly && !window.confirm('此题尚未到期。提前练习会更新复习安排，同日重练不会升级阶段。确定继续吗？')) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    await sync.enqueue({ kind: 'review.write', name: action === 'RATE' ? `评分：${ratings[rating!]}` : action === 'UNDO' ? '撤销最近评分' : action === 'SKIP' ? '跳过重练' : '重新学习', action,
      expectedVersion: s.version, payload: { sessionId: s.id, itemId: item?.id, expectedReviewVersion: item?.expectedVersion,
        ...(rating ? { rating } : {}), answerRevealed: answer.value, confirmEarly, confirmed: action === 'RELEARN', eventId: s.events[0]?.id } })
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '本地保留失败，请重试' }
  finally { busy.value = false }
}
watch(() => sync.items.filter(i => i.status === 'synced').map(i => i.operationId).join(','), async () => {
  for (const i of sync.items) {
    if (i.status !== 'synced' || seen.has(i.operationId)) continue
    seen.add(i.operationId)
    if (i.kind === 'session.start' && !route.params.id && JSON.stringify(i.payload?.cardIds) === JSON.stringify(cardIds.value) && i.result?.session) {
      await router.replace(`/practice/${i.result.session.id}`)
    } else if (i.kind === 'review.write' && i.payload?.sessionId === route.params.id && i.result?.session) {
      message.value = i.action === 'UNDO' ? '已撤销，评分前的状态和队列已恢复。' : i.action === 'SKIP' ? '已跳过重练，保留复习安排。'
        : `已保存。下次复习：${i.result.dueDate}${i.result.isReplay ? ' · 同日重练，阶段不升级' : ''}`
      await apply(i.result.session)
      // A recovered receipt may predate another device's subsequent session advancement.
      await load()
    }
  }
})
watch(() => route.fullPath, () => { generation++; session.value = undefined; card.value = undefined; answer.value = false; hint.value = false; void load() }, { immediate: true })
watch(pending, value => { if (!value && sync.items.some(i => i.kind === 'review.write' && i.payload?.sessionId === route.params.id && i.result?.conflict?.resolved)) void load() })
onMounted(() => window.addEventListener('focus', load))
onUnmounted(() => { generation++; window.removeEventListener('focus', load) })
onSyncRefresh(load)
</script>
<template>
  <div class="sync-actions"><RouterLink to="/">← 返回今日学习 / 稍后继续</RouterLink><RouterLink to="/cards">返回题库</RouterLink></div>
  <section class="sources-intro"><p class="eyebrow">RECALL · 主动回忆</p><h1>先回忆，再核对答案</h1><p>三档自评按服务端上海学习日安排复习。同日重练保留评分，阶段不升级。</p></section>
  <p v-if="error" role="alert" class="form-error">{{ error }}</p>
  <p v-if="message" role="status" class="notice">{{ message }}</p>
  <p v-if="needsAttention" role="alert">操作未获确认，请到“保存与同步”核对并处理。失败或冲突不会自动重试，也不会进入下一题。</p>
  <p v-else-if="pending" role="status">操作已保留，请等待“保存与同步”的服务端确认。确认前不会进入下一题。</p>
  <p v-if="loading" role="status">正在读取练习…</p>
  <section v-if="!route.params.id" class="chapter-review">
    <p v-if="selected">准备练习 {{ cardIds.length }} 题。已学题若尚未到期，评分前会提示确认。</p><p v-else>请从题库选择一道题或练习当前页。</p>
    <button class="primary-button" :disabled="!selected || busy || pending" @click="start">开始主动回忆</button>
  </section>
  <section v-if="session" class="chapter-review">
    <p>学习日 {{ session.learningDate }} · 本轮已处理 {{ session.items.filter(i => i.completed).length }} / {{ session.items.length }} 项（含重练）</p>
    <p v-if="session.round">本轮新学 {{ session.round.newCount }} · 复习 {{ session.round.reviewCount }} · 仍需关注 {{ session.round.attention.length }} 题</p>
    <p v-if="session.status === 'INVALIDATED'" role="alert">题库已删除或进度已重置，此会话已失效。请返回今日学习重新进入，旧会话不能继续评分。</p>
    <p v-else-if="session.expired" role="alert">会话已跨天，请返回今日学习重新生成计划，按新学习日核算额度和到期题，已保存的记录会保留。</p>
    <p v-if="session.items.some(i => i.unavailable)" class="notice">已跳过暂停章节、已由其他会话处理或超出今日新题额度的条目。</p>
    <p v-if="session.current && session.current.currentVersion !== session.current.expectedVersion" role="alert">此题已有后续学习。请读取最新状态并返回题库重新进入练习。</p>
    <article v-if="card && session.current" class="card-detail">
      <p class="eyebrow">{{ card.moduleName }} / {{ card.chapterName }}{{ session.current.isReplay ? ' · 本轮追加重练（可跳过）' : '' }}</p>
      <h2>{{ card.question }}</h2><p v-if="card.needsCheck" class="check-reminder">待检查：请继续对照来源核对。</p>
      <p v-if="session.review">最近{{ ratings[session.review.latestRating] }} · 下次复习 {{ session.review.dueDate }} · 遗忘 {{ session.review.lapseCount }} 次</p>
      <p v-if="early" class="form-note">本题尚未到期，提前评分会更新复习安排；同日重练不会延长原日期。</p>
      <div class="sync-actions"><button class="primary-button" :aria-expanded="answer" @click="answer = !answer">{{ answer ? '收起完整答案' : '展开完整答案' }}</button><button class="secondary-button" :aria-expanded="hint" @click="hint = !hint">{{ hint ? '收起提示与要点' : '查看提示与要点' }}</button></div>
      <aside v-if="hint"><p class="prose-text">{{ card.annotation.hint || '尚未填写提示' }}</p><p class="prose-text">{{ card.annotation.memoryPoints }}</p><p class="form-note">使用提示后请按独立回忆程度评分。</p></aside>
      <section v-if="answer" aria-label="完整答案" class="draft-preview"><CardAnswer :card="card" />
        <aside v-if="card.technicalCorrection" class="technical-correction"><h3>个人技术纠错（人工补充）</h3><p class="prose-text">{{ card.technicalCorrection }}</p></aside>
        <details v-for="(f, index) in card.followups" :key="`${card.id}-${index}`" class="followup"><summary>追问：{{ f.title }}</summary><pre class="source-text">{{ f.text }}</pre></details>
      </section>
      <div v-if="answer" class="sync-actions" aria-label="三档自评"><button v-for="(label, rating) in ratings" :key="rating" class="primary-button" :disabled="blocked || !card" @click="write('RATE', String(rating))">{{ label }}</button></div>
      <p v-else class="form-note">回忆完成后展开完整答案，即可进行三档自评。</p>
      <details><summary>个人笔记与来源核对</summary><p class="prose-text">{{ card.annotation.notes }}</p><ul><li v-for="(source, index) in card.sources" :key="index"><RouterLink :to="{ path: `/sources/${source.sourceId}/read`, query: { page: source.page, bbox: source.bbox.join(','), card: card.id } }">{{ source.name }} · 物理页 {{ source.page }}</RouterLink></li></ul></details>
      <div class="sync-actions"><button v-if="session.current.isReplay" class="secondary-button" :disabled="blocked" @click="write('SKIP')">跳过重练</button><button v-if="session.review" class="secondary-button" :disabled="blocked" @click="write('RELEARN')">重新学习此题</button></div>
    </article>
    <p v-else-if="!session.current && session.status !== 'INVALIDATED'" role="status">本轮练习结束。已保存的评分将用于后续复习安排。</p>
    <section v-if="!session.current && session.round && session.status !== 'INVALIDATED'" aria-label="本轮回顾"><h2>本轮回顾</h2><p>本轮结束表示本批条目已处理，不代表全部题目已经掌握。首页可查看剩余到期题并开始下一轮。</p><ul><li v-for="c in session.round.attention" :key="c.id"><RouterLink :to="`/cards/${c.id}`">{{ c.question }}</RouterLink> · 最近{{ ratings[c.latestRating] }} · 遗忘 {{ c.lapseCount }} 次</li></ul><RouterLink to="/">返回今日学习，查看剩余任务</RouterLink></section>
    <p v-if="session.events.length && !session.canUndo" class="form-note">此记录已有后续操作，当前不能直接撤销。</p>
    <div class="sync-actions"><button v-if="session.canUndo" class="secondary-button" :disabled="blocked" @click="write('UNDO')">{{ session.events[0]?.kind === 'RELEARN' ? '撤销重新学习' : '撤销最近一次评分' }}</button><button class="secondary-button" :disabled="pending || busy" @click="load">读取最新状态</button></div>
  </section>
  <button v-else-if="route.params.id && !loading" class="secondary-button" @click="load">重试读取练习</button>
</template>
