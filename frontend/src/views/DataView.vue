<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { deckDataApi, type DeckImpact } from '../api/deckData'
import { useSyncStore } from '../stores/sync'
import { onSyncRefresh } from '../sync/refresh'
const sync = useSyncStore(), decks = ref<{ id: string; name: string }[]>([]), selected = ref(''), action = ref('RESET')
const impact = ref<DeckImpact>(), confirmed = ref(false), loading = ref(false), busy = ref(false), error = ref(''), message = ref('')
const pending = computed(() => sync.items.some(i => i.status !== 'synced'))
const stale = computed(() => !impact.value || impact.value.dataEpoch !== sync.state?.dataEpoch || impact.value.revision !== sync.state?.revision)
let generation = 0
const seen = new Set(sync.items.filter(i => i.status === 'synced').map(i => i.operationId))
function clear() { generation++; impact.value = undefined; confirmed.value = false; error.value = ''; message.value = '' }
watch([selected, action], clear)
async function load() {
  try { decks.value = await deckDataApi.decks() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '读取题库失败，请重试' }
}
async function preview() {
  clear(); const current = generation; loading.value = true
  try {
    await sync.refresh(true)
    const value = await deckDataApi.preview(selected.value)
    if (current === generation) impact.value = value
  } catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '预览失败，请重试' }
  finally { loading.value = false }
}
async function submit() {
  if (!impact.value || stale.value || pending.value || !confirmed.value || busy.value || loading.value) return
  busy.value = true; error.value = ''
  try {
    await sync.enqueue({ kind: 'deck.data', name: `${action.value === 'DELETE' ? '删除题库' : '重置进度'}：${impact.value.name}`,
      action: action.value, expectedVersion: impact.value.revision, payload: { deckId: impact.value.deckId } })
    confirmed.value = false; message.value = '操作已保留，等待服务端确认。请在“保存与同步”核对结果，当前尚未宣告完成。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作保留失败，请重试' }
  finally { busy.value = false }
}
watch(() => sync.items, items => {
  for (const i of items) if (i.kind === 'deck.data' && i.status === 'synced' && !seen.has(i.operationId)) {
    seen.add(i.operationId); clear(); message.value = i.action === 'DELETE' ? '题库已删除，原 PDF 保留在我的资料。' : '学习进度已重置，卡片、笔记和收藏已保留。'
    void load()
  }
})
watch(stale, value => { if (value) confirmed.value = false })
onSyncRefresh(load)
onMounted(load)
onUnmounted(() => { generation++ })
</script>
<template>
  <RouterLink to="/settings">← 返回学习设置</RouterLink>
  <section class="sources-intro"><p class="eyebrow">DATA · 数据操作</p><h1>管理题库与学习记录</h1><p>先查看影响，再确认执行。建议先<RouterLink to="/backups">导出完整备份</RouterLink>。</p></section>
  <p v-if="error" role="alert" class="form-error">{{ error }} <button class="secondary-button" @click="load">重新读取题库</button></p>
  <p v-if="message" role="status" class="notice">{{ message }}</p>
  <p v-if="pending" class="notice">有尚未同步或待处理的操作，请先到“保存与同步”核对；预览过期的操作需放弃后重新预览。</p>
  <section class="chapter-review data-operations">
    <p v-if="!decks.length">还没有题库。<RouterLink to="/sources">上传 PDF 并检查章节</RouterLink></p>
    <label>操作题库<select v-model="selected" :disabled="busy || loading"><option value="">请选择题库</option><option v-for="d in decks" :key="d.id" :value="d.id">{{ d.name }}</option></select></label>
    <label>操作类型<select v-model="action" :disabled="busy || loading"><option value="RESET">重置学习进度</option><option value="DELETE">删除题库内容</option></select></label>
    <button class="secondary-button" :disabled="!selected || loading || busy" @click="preview">{{ loading ? '正在读取影响…' : '读取影响预览' }}</button>
    <section v-if="impact" class="confirm-panel" aria-label="数据操作影响预览">
      <h2>{{ action === 'DELETE' ? '删除题库' : '重置进度' }}：{{ impact.name }}</h2>
      <p>本题库有 {{ impact.counts.cards }} 张正式卡片、{{ impact.counts.drafts }} 张草稿、{{ impact.counts.annotations }} 份笔记/提示/收藏、{{ impact.counts.learned }} 张已学卡片、{{ impact.counts.events }} 条学习事件。涉及 {{ impact.counts.sessions }} 个学习会话。</p>
      <p v-if="action === 'DELETE'">将删除本题库全部正式卡片、草稿、检查记录、笔记、提示、收藏及学习历史，移出学习范围。原 PDF 和图片资源保留在我的资料，可显式重试解析重新导入。此操作不能直接撤销。</p>
      <p v-else>将清除本题库全部评分、撤销、更正、重练历史及复习日期，卡片回到未学，之后学习会重新计为新题。卡片内容、草稿、笔记、提示、收藏和学习设置保留。此操作不能直接撤销。</p>
      <p>相关会话（含跨题库批次）将失效，其他题库的卡片和历史保留；其他设备需刷新并重新进入学习。仅需巩固一题时，可在练习中选择“重新学习此题”，保留历史并将复习日期设为今日。</p>
      <p v-if="stale" role="alert">数据已变化，请重新读取影响预览并确认。</p>
      <label><input v-model="confirmed" type="checkbox" :disabled="stale || pending || busy" />我已核对影响，确认{{ action === 'DELETE' ? '删除题库内容及其历史' : '清除学习历史并重置进度' }}。</label>
      <button class="primary-button danger-button" :disabled="!confirmed || stale || pending || busy || loading" @click="submit">确认{{ action === 'DELETE' ? '删除题库' : '重置进度' }}</button>
    </section>
  </section>
</template>
