<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { learningApi, type TodayLearning } from '../api/learning'
import { useSyncStore } from '../stores/sync'
import { onSyncRefresh } from '../sync/refresh'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
const route = useRoute(), deckFilter = ref(String(route.query.deckId ?? ''))
const sync = useSyncStore(), today = ref<TodayLearning>(), loading = ref(false), busy = ref(false), error = ref(''), message = ref('')
const dailyNewCount = ref(5), reviewBatchSize = ref(20), referenceMinutes = ref<number | ''>(''), chapterIds = ref<string[]>([])
const baseline = ref('')
const decks = computed(() => [...new Map(today.value?.chapters.map(ch => [ch.deckId, { id: ch.deckId, name: ch.deckName }]) ?? []).values()])
const visibleChapters = computed(() => today.value?.chapters.filter(ch => !deckFilter.value || ch.deckId === deckFilter.value) ?? [])
function selectVisible(selected: boolean) {
  const ids = new Set(visibleChapters.value.map(ch => ch.id))
  chapterIds.value = selected ? [...new Set([...chapterIds.value, ...ids])] : chapterIds.value.filter(id => !ids.has(id))
}
watch(() => route.query.deckId, value => { deckFilter.value = String(value ?? '') })
const formValue = () => JSON.stringify([dailyNewCount.value, reviewBatchSize.value, referenceMinutes.value, chapterIds.value])
const dirty = computed(() => !!baseline.value && baseline.value !== formValue())
const pending = computed(() => sync.items.some(i => i.kind === 'preferences.save' && i.status !== 'synced'))
const valid = computed(() => Number.isInteger(dailyNewCount.value) && dailyNewCount.value >= 0 && dailyNewCount.value <= 100
  && Number.isInteger(reviewBatchSize.value) && reviewBatchSize.value >= 1 && reviewBatchSize.value <= 100
  && (referenceMinutes.value === '' || Number.isInteger(referenceMinutes.value) && referenceMinutes.value >= 1 && referenceMinutes.value <= 1440))
const seen = new Set(sync.items.filter(i => i.status === 'synced').map(i => i.operationId))
const restoring = new Set<string>()
watch(() => sync.items, items => {
  for (const i of items) if (i.kind === 'preferences.save' && i.status !== 'synced') restoring.add(i.operationId)
}, { immediate: true })
let submittedAfter = Infinity
async function load(replaceEdits = false) {
  loading.value = true; error.value = ''
  try {
    const t = await learningApi.today()
    if (dirty.value && !replaceEdits) { message.value = '服务端数据已更新，本地设置编辑已保留。'; return }
    today.value = t
    dailyNewCount.value = t.preferences.dailyNewCount; reviewBatchSize.value = t.preferences.reviewBatchSize
    referenceMinutes.value = t.preferences.referenceMinutes ?? ''; chapterIds.value = [...t.preferences.chapterIds]
    baseline.value = formValue()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取设置失败' }
  finally { loading.value = false }
}
async function save() {
  if (!valid.value || pending.value || busy.value || !today.value || !sync.state) return
  busy.value = true; error.value = ''; message.value = ''
  submittedAfter = Date.now()
  try {
    await sync.enqueue({ kind: 'preferences.save', name: '每日目标与学习范围', expectedVersion: today.value.preferences.version,
      payload: { dailyNewCount: dailyNewCount.value, reviewBatchSize: reviewBatchSize.value, referenceMinutes: referenceMinutes.value === '' ? null : referenceMinutes.value, chapterIds: chapterIds.value } })
    message.value = '设置已保留，等待服务端确认。'
    baseline.value = formValue()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保留设置失败，请重试' }
  finally { busy.value = false }
}
function reloadSettings() { if (!dirty.value || window.confirm('重新读取将放弃未保存的学习设置，确定继续吗？')) void load(true) }
watch(() => sync.items.filter(i => i.status === 'synced' && i.kind === 'preferences.save').map(i => i.operationId).join(','), async () => {
  for (const i of sync.items) if (i.kind === 'preferences.save' && i.status === 'synced' && !seen.has(i.operationId) && (i.createdAt >= submittedAfter || restoring.has(i.operationId))) {
    seen.add(i.operationId); message.value = '学习设置已同步。已保存的学习历史与到期日期保持不变。'; await load()
  }
})
onMounted(load)
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
onSyncRefresh(() => {
  if (dirty.value || pending.value || busy.value) { message.value = '服务端数据已更新，本地设置编辑已保留。保存时将核对版本。'; return }
  return load()
}, ['learning_preference', 'learning_preference_chapter', 'chapter', 'deck', 'module', 'card', 'review_state', 'review_event'])
onBeforeRouteLeave(() => !dirty.value || window.confirm('还有未保存的学习设置，确定离开并放弃吗？'))
</script>
<template>
  <RouterLink to="/">← 返回今日学习</RouterLink>
  <section class="sources-intro"><p class="eyebrow">PREFERENCES · 学习设置</p><h1>安排自己的学习节奏</h1><p>设置影响后续计划；暂停章节会跳过该章的每日任务，历史、笔记和复习日期仍会保留。</p></section>
  <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="message" role="status" class="notice">{{ message }}</p>
  <p v-if="loading" role="status">正在读取设置…</p>
  <p v-if="dirty" role="status">有未保存的学习设置，请保存后再离开。</p>
  <p><RouterLink to="/review-parameters">复习参数与效果评估</RouterLink> · <RouterLink to="/data">题库删除与学习进度重置</RouterLink> · <RouterLink to="/backups">备份与恢复</RouterLink></p>
  <form v-if="today" class="chapter-review learning-settings" @submit.prevent="save">
    <fieldset :disabled="pending || busy || loading"><legend>每日目标</legend>
      <label>每日新题量<input v-model.number="dailyNewCount" type="number" min="0" max="100" required /></label><p class="form-note">0～100，设为 0 可专注复习。今日剩余额度按目标减去今日不同新学卡片数计算。</p>
      <label>每轮复习批量<input v-model.number="reviewBatchSize" type="number" min="1" max="100" required /></label><p class="form-note">1～100，只限制本轮数量，首页仍显示全部到期总数。</p>
      <label>每日参考时长（分钟，可选）<input v-model.number="referenceMinutes" type="number" min="1" max="1440" /></label><p class="form-note">留空表示不设置；不会强制计时或到时结束。</p>
    </fieldset>
    <fieldset :disabled="pending || busy || loading"><legend>参与学习的章节</legend><p v-if="!today.chapters.length">还没有已确认的章节，请先到我的资料完成逐章检查。</p>
      <label>按题库查看学习范围<select v-model="deckFilter"><option value="">全部题库</option><option v-for="d in decks" :key="d.id" :value="d.id">{{ d.name }} · {{ d.id }}</option></select></label>
      <p>共选中 {{ chapterIds.length }} 章。切换查看保留其他题库的选择；每日目标由全部选中章节共用。</p>
      <p v-if="deckFilter && !visibleChapters.length">该题库不存在或暂无已确认章节，请返回题库管理核对。</p>
      <div class="sync-actions"><button type="button" class="secondary-button" :disabled="!visibleChapters.length" @click="selectVisible(true)">选中当前显示章节</button><button type="button" class="secondary-button" :disabled="!visibleChapters.length" @click="selectVisible(false)">暂停当前显示章节</button></div>
      <label v-for="ch in visibleChapters" :key="ch.id" class="chapter-choice"><input v-model="chapterIds" type="checkbox" :value="ch.id" /><span>{{ ch.deckName }} · {{ ch.deckId }} · {{ ch.moduleName }} / {{ ch.name }}<small>已学 {{ ch.learned }}/{{ ch.total }} · 到期 {{ ch.due }} · 薄弱 {{ ch.weak }}</small></span></label>
    </fieldset>
    <p v-if="pending" role="status">设置尚未确认，请查看“保存与同步”；冲突时保留本地与服务端内容供核对。</p>
    <p v-if="!sync.state" role="status">正在连接同步服务，连接后可保存；当前输入会保留。</p>
    <div class="sync-actions"><button class="primary-button" type="submit" :disabled="!valid || pending || busy || loading || !sync.state">保存学习设置</button><button class="secondary-button" type="button" :disabled="pending || busy || loading" @click="reloadSettings">重新读取设置</button></div>
  </form>
  <button v-else-if="!loading" class="secondary-button" @click="load()">重试读取设置</button>
</template>
