<script setup lang="ts">
import { libraryReviewApi, libraryWrite } from '../api/library'
import { onSyncRefresh } from '../sync/refresh'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { importsApi, type ChapterReview, type Draft } from '../api/imports'
import { useSyncStore } from '../stores/sync'
import DraftFields from './DraftFields.vue'
import DraftPreview from './DraftPreview.vue'
import DraftSourceComparison from './DraftSourceComparison.vue'
import { similarDrafts } from '../review/similarity'
const props = defineProps<{ id: string; libraryId?: string; readonly?: boolean }>()
const api = props.libraryId ? libraryReviewApi(props.libraryId) : importsApi
const emit = defineEmits<{ changed: []; dirty: [value: boolean] }>()
const sync = useSyncStore()
const review = ref<ChapterReview>()
const draftId = ref('')
const draft = ref<Draft>()
const selected = ref<string[]>([])
const reason = ref('')
const error = ref('')
const notice = ref('')
const dirty = ref(false)
const editing = ref(false)
const splitParts = ref<Draft[]>([])
const confirmOpen = ref(false)
const allowFlagged = ref(false)
const abandonOpen = ref(false)
const enqueuing = ref(false)
const abandonedChoice = ref(false)
const listMode = ref('ALL')
const keyword = ref('')
const compareSource = ref(false)
const comparisonId = ref('')
const comparison = ref<Draft>()
const comparisonError = ref('')
let stopped = false
let generation = 0
let detailGeneration = 0
let awaitingSave = false
const pending = computed(() => !props.libraryId && sync.items.some(i => i.kind === 'chapter.review' && i.chapterId === props.id && i.status !== 'synced'))
const locked = computed(() => enqueuing.value || pending.value || props.readonly || (!props.libraryId && !sync.state))
const imported = computed(() => review.value?.checkStatus === 'IMPORTED')
const editable = computed(() => !review.value?.abandoned && !draft.value?.excluded)
const flagKinds = computed(() => {
  const counts = new Map<string, number>()
  for (const item of review.value?.items ?? []) {
    if (!item.excluded) for (const kind of new Set(item.flags.map(f => f.kind))) counts.set(kind, (counts.get(kind) ?? 0) + 1)
  }
  if (listMode.value.startsWith('kind:') && !counts.has(listMode.value.slice(5))) counts.set(listMode.value.slice(5), 0)
  return [...counts].sort(([a], [b]) => a.localeCompare(b))
})
const visibleItems = computed(() => (review.value?.items ?? []).filter(item => {
  const mode = listMode.value
  const inScope = mode === 'ALL' || (mode === 'EXCLUDED' ? item.excluded : !item.excluded && (mode === 'FLAGGED' ? item.flags.length > 0 : item.flags.some(f => `kind:${f.kind}` === mode)))
  return inScope && item.question.toLocaleLowerCase().includes(keyword.value.trim().toLocaleLowerCase())
}))
const flaggedItems = computed(() => visibleItems.value.filter(item => !item.excluded && item.flags.length > 0))
function adjacentFlagged(direction: number) {
  const items = flaggedItems.value
  if (!items.length) return undefined
  const index = items.findIndex(item => item.id === draftId.value)
  return items[index < 0 ? (direction > 0 ? 0 : items.length - 1) : (index + direction + items.length) % items.length]
}
const similarities = computed(() => {
  const current = review.value?.items.find(item => item.id === draftId.value)
  return current ? similarDrafts(current, review.value!.items) : []
})
watch([listMode, keyword], () => { selected.value = [] })
watch(draftId, () => { comparisonId.value = '' })
watch(comparisonId, async (id, _, onCleanup) => {
  let stale = false; onCleanup(() => { stale = true })
  comparison.value = undefined; comparisonError.value = ''
  if (!id) return
  try { const value = await api.draft(id); if (!stopped && !stale) comparison.value = value }
  catch (cause) { if (!stopped && !stale) comparisonError.value = cause instanceof Error ? cause.message : '对比草稿读取失败' }
})
const names: Record<string, string> = { PENDING: '待检查', IN_PROGRESS: '检查中', IMPORTED: '已导入' }
const flagNames: Record<string, string> = { GLYPH_MAPPING: '正文字体映射', TOC_GLYPH_MAPPING: '目录字体映射', SPECIAL_CHARACTER: '特殊字符', IMAGE_REQUIRES_SOURCE: '图片核对', EMPTY_PAGE: '空白来源页', LAYOUT_REQUIRES_SOURCE: '段落、代码与表格', MANUAL_CHECK: '人工待检查', TABLE: '表格核对', GLYPH: '字形核对' }
const actions: Record<string, string> = { EDIT: '编辑', MERGE: '合并', SPLIT: '拆分', EXCLUDE: '排除', PROGRESS: '保存检查位置', CONFIRM: '确认章节', ABANDON: '放弃剩余草稿' }
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T
watch(dirty, value => emit('dirty', value))
watch(draft, () => { if (editing.value) dirty.value = true }, { deep: true })
watch(splitParts, () => { if (splitParts.value.length) dirty.value = true }, { deep: true })
async function load() {
  const current = ++generation
  try {
    const value = await api.review(props.id)
    if (stopped || current !== generation) return
    if (dirty.value) { notice.value = '有尚未保存的修改，请先保存或取消编辑，再读取服务端内容。'; return }
    review.value = value; error.value = ''; comparisonId.value = ''
    const id = value.currentDraftId ?? value.items.find(d => !d.excluded)?.id ?? value.items[0]?.id ?? ''
    await openDraft(id, false)
  } catch (cause) { if (!stopped && current === generation) error.value = cause instanceof Error ? cause.message : '章节读取失败' }
}
async function openDraft(id: string, savePosition = true) {
  if (dirty.value) { notice.value = '请先保存或取消当前修改。'; return }
  const current = ++detailGeneration
  draftId.value = id; draft.value = undefined; editing.value = false; splitParts.value = []; error.value = ''
  if (!id) return
  try {
    const value = await api.draft(id)
    if (stopped || current !== detailGeneration) return
    draft.value = value
    if (savePosition && !review.value?.abandoned && !locked.value) await submit('PROGRESS', { draftId: id })
  } catch (cause) { if (!stopped && current === detailGeneration) error.value = cause instanceof Error ? cause.message : '草稿读取失败' }
}
async function submit(action: string, payload: Record<string, unknown>) {
  if (!review.value || locked.value) return
  error.value = ''; notice.value = ''; enqueuing.value = true; awaitingSave = action !== 'PROGRESS'
  try {
    if (props.libraryId) {
      await libraryWrite(`/api/admin/library/${props.libraryId}/chapters/${props.id}/review`, { expectedVersion: review.value.version, action, payload })
      editing.value = false; splitParts.value = []; dirty.value = false; awaitingSave = false; confirmOpen.value = false; abandonOpen.value = false; selected.value = []
      notice.value = '服务端已确认保存。'; emit('changed'); await load(); return
    }
    await sync.enqueue({ kind: 'chapter.review', chapterId: props.id, name: `${review.value.name} · ${actions[action]}`, expectedVersion: review.value.version, action, payload })
    // IndexedDB now owns the complete operation; leaving is safe even before the server acknowledges it.
    if (action !== 'PROGRESS') { editing.value = false; splitParts.value = []; dirty.value = false }
    notice.value = '操作已在本机保留，收到服务端确认后才算保存成功。请在“保存与同步”查看结果。'
  } catch (cause) { awaitingSave = false; error.value = cause instanceof Error ? cause.message : '本地保留失败' }
  finally { enqueuing.value = false }
}
watch(() => sync.items.filter(i => i.chapterId === props.id && i.status === 'synced').map(i => i.operationId).join(','), async () => {
  if (pending.value) return
  if (awaitingSave) { editing.value = false; splitParts.value = []; dirty.value = false; awaitingSave = false; confirmOpen.value = false; abandonOpen.value = false; selected.value = [] }
  notice.value = '服务端已确认保存。'; emit('changed'); await load()
})
function startEdit() { editing.value = true; reason.value = ''; notice.value = '' }
async function cancel() { editing.value = false; splitParts.value = []; dirty.value = false; reason.value = ''; await openDraft(draftId.value, false) }
function valueOf(d: Draft) {
  // Every changed block is explicitly a display correction; extraction evidence remains immutable.
  return { question: d.question, contentBlocks: d.contentBlocks.map(b => ({ ...b, type: 'MANUAL_TEXT' })), sources: d.sources, flags: d.flags, followups: d.followups }
}
async function saveEdit() {
  if (!draft.value || !reason.value.trim()) { error.value = '请填写本次调整原因。'; return }
  await submit('EDIT', { draftId: draftId.value, reason: reason.value, value: valueOf(draft.value) })
}
function startSplit() {
  if (!draft.value) return
  const first = clone(draft.value); const second = clone(draft.value)
  second.question += '（拆分）'
  // Keep every character and source initially; the user chooses the actual boundary before saving.
  splitParts.value = [first, second]; editing.value = false; reason.value = ''; dirty.value = true
}
async function saveSplit() {
  if (!reason.value.trim()) { error.value = '请填写拆分原因，并核对两题的完整内容与来源。'; return }
  await submit('SPLIT', { draftId: draftId.value, reason: reason.value, parts: splitParts.value.map(valueOf) })
}
async function adjust(action: 'MERGE' | 'EXCLUDE') {
  if (!reason.value.trim()) { error.value = '请填写本次调整原因。'; return }
  await submit(action, { reason: reason.value, ...(action === 'MERGE' ? { draftIds: selected.value } : { draftId: draftId.value }) })
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
function focus() { if (!dirty.value && !pending.value && !enqueuing.value) void load() }
onBeforeRouteLeave(() => !dirty.value || window.confirm('当前修改尚未保存，确定暂时退出？已保存草稿和正式卡片会保留。'))
onMounted(() => { void load(); window.addEventListener('beforeunload', beforeUnload); window.addEventListener('focus', focus) })
onUnmounted(() => { stopped = true; generation++; detailGeneration++; window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('focus', focus); emit('dirty', false) })
onSyncRefresh(load)
</script>
<template>
  <section class="chapter-review" aria-label="逐章检查">
    <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <p v-if="pending" role="status">本章有待同步或待处理操作，请先在“保存与同步”处理；本地内容已保留。</p>
    <button class="secondary-button" :disabled="dirty || pending || enqueuing" @click="load">读取最新章节与检查位置</button>
    <template v-if="review">
      <h2>{{ review.name }} · {{ review.abandoned ? '已放弃剩余草稿' : names[review.checkStatus] }}</h2>
      <p>当前有效 {{ review.activeCount }} 题 · {{ review.flaggedCount }} 题待检查 · 用户调整 {{ review.userCountDelta > 0 ? '+' : '' }}{{ review.userCountDelta }} 题</p>
      <p class="muted">识别差异见上方原始报告；用户调整单独记录。{{ imported ? '修正将更新现有正式卡片，保留 ID 和学习记录。' : '其他章节继续保留草稿。' }}</p>
      <section class="draft-list" aria-label="候选问题"><h3>{{ imported ? '正式卡片及来源草稿' : '候选问题' }}（{{ review.items.length }}）</h3>
        <div class="review-filters">
          <label>检查范围<select v-model="listMode" aria-label="检查范围" :disabled="dirty || locked">
            <option value="ALL">全部（含已排除）</option><option value="FLAGGED">仅待检查（{{ review.flaggedCount }} 题）</option><option value="EXCLUDED">已排除</option>
            <option v-for="[kind, count] in flagKinds" :key="kind" :value="`kind:${kind}`">{{ flagNames[kind] ?? kind }}（{{ count }} 题）</option>
          </select></label>
          <label>查找题目<input v-model="keyword" aria-label="查找候选题目" maxlength="200" :disabled="dirty || locked" placeholder="按题目关键词查找" /></label>
        </div>
        <p role="status">当前显示 {{ visibleItems.length }} / {{ review.items.length }} 题；筛选不改变章节确认范围。</p>
        <div class="sync-actions">
          <button class="secondary-button" :disabled="dirty || locked || !adjacentFlagged(-1) || adjacentFlagged(-1)?.id === draftId" @click="openDraft(adjacentFlagged(-1)!.id)">上一待检查题</button>
          <button class="secondary-button" :disabled="dirty || locked || !adjacentFlagged(1) || adjacentFlagged(1)?.id === draftId" @click="openDraft(adjacentFlagged(1)!.id)">下一待检查题</button>
        </div>
        <p v-if="!visibleItems.length">当前范围没有匹配的题目，可切换检查范围或清空关键词。</p>
        <p v-if="draftId && !visibleItems.some(item => item.id === draftId)" class="form-note">当前打开的题目在筛选范围外；选择下方题目或下一待检查题继续。</p>
        <ol><li v-for="item in visibleItems" :key="item.id">
          <input v-if="!imported && !item.excluded && !review.abandoned" v-model="selected" type="checkbox" :value="item.id" :aria-label="`选择合并：${item.question}`" :disabled="locked || dirty" />
          <button class="draft-link" :aria-pressed="draftId === item.id" :disabled="dirty || locked" @click="openDraft(item.id)">{{ item.question }}</button>
          <small>{{ item.excluded ? ' · 已排除' : ` · ${item.flags.length} 处待检查` }}</small>
          <ul v-if="listMode !== 'ALL' && !item.excluded && item.flags.length" class="flag-reasons"><li v-for="(flag, index) in item.flags" :key="index">{{ flagNames[flag.kind] ?? flag.kind }} · {{ flag.page ? `物理页 ${flag.page} · ` : '' }}{{ flag.reason }}</li></ul>
        </li></ol>
      </section>
      <section v-if="draft" class="similarity-panel" aria-label="相似题提示">
        <h3>本章相似题提示（{{ similarities.length }}）</h3>
        <p class="form-note">仅比较本章未排除题目的标题文字，可能漏报或误报。请核对完整答案和来源，再手动选择是否合并；提示不会自动选中、修改或排除题目。</p>
        <p v-if="!similarities.length">当前题目暂无相似标题提示。</p>
        <ul v-else><li v-for="match in similarities.slice(0, 5)" :key="match.item.id">
          {{ match.item.question }} · 标题相似度 {{ Math.round(match.score * 100) }}%
          <button class="secondary-button" :aria-pressed="comparisonId === match.item.id" @click="comparisonId = comparisonId === match.item.id ? '' : match.item.id">{{ comparisonId === match.item.id ? '收起对比' : '只读对比' }}</button>
        </li></ul>
        <p v-if="similarities.length > 5" class="form-note">显示最相似的前 5 题，其余题目可在候选列表查找。</p>
        <p v-if="comparisonId && !comparison && !comparisonError" role="status">正在读取对比题目…</p>
        <p v-if="comparisonError" role="alert">{{ comparisonError }}，请收起后重新对比。</p>
        <DraftPreview v-if="comparison" :id="comparison.id" :snapshot="comparison" :library-id="libraryId" />
      </section>
      <div class="sync-actions"><button class="secondary-button" :aria-pressed="compareSource" @click="compareSource = !compareSource">{{ compareSource ? '收起原文对照' : '原文并排对照' }}</button></div>
      <p v-if="draft" class="form-note">章节版本 {{ review.version }} · 草稿版本 {{ draft.version }} · 草稿 ID {{ draft.id }}{{ dirty ? ' · 当前有未保存修改' : '' }}</p>
      <div class="review-workspace" :class="{ 'with-source': compareSource && draft }">
      <div class="review-content">
      <div v-if="!review.abandoned" class="review-actions">
        <label for="review-reason">本次调整原因<input id="review-reason" v-model="reason" maxlength="2000" :disabled="locked" placeholder="说明内容或数量调整的原因" /></label>
        <div class="sync-actions">
          <button v-if="draft && editable && !editing && !splitParts.length" class="secondary-button" :disabled="locked" @click="startEdit">{{ imported ? '修正现有正式卡片' : '编辑问题、内容与来源' }}</button>
          <template v-if="!imported && !dirty"><button class="secondary-button" :disabled="locked || selected.length < 2" @click="adjust('MERGE')">合并选中题（{{ selected.length }}）</button>
            <button class="secondary-button" :disabled="locked || !draft || !editable" @click="startSplit">拆分当前题</button>
            <button class="secondary-button" :disabled="locked || !draft || !editable" @click="adjust('EXCLUDE')">排除当前题</button></template>
        </div>
      </div>
      <form v-if="editing && draft" class="review-editor" @submit.prevent="saveEdit">
        <p>编辑后的内容作为人工展示修订，原始提取证据仍保留。{{ dirty ? '有未保存修改' : '尚未修改' }}</p>
        <fieldset :disabled="locked"><DraftFields v-model="draft" prefix="edit" /></fieldset>
        <div class="sync-actions"><button class="primary-button" :disabled="locked">保存编辑</button><button type="button" class="secondary-button" :disabled="locked" @click="cancel">取消编辑</button></div>
      </form>
      <form v-if="splitParts.length" class="review-editor" @submit.prevent="saveSplit">
        <p>两题初始均保留全文和全部来源。请分别编辑问题、内容和来源边界，删除重复部分后保存；第二题关联原条目。</p>
        <fieldset v-for="(_, index) in splitParts" :key="index" :disabled="locked"><legend>拆分结果 {{ index + 1 }}</legend><DraftFields v-model="splitParts[index]!" :prefix="`split-${index}`" /></fieldset>
        <div class="sync-actions"><button class="primary-button" :disabled="locked">保存拆分为两题</button><button type="button" class="secondary-button" :disabled="locked" @click="cancel">取消拆分</button></div>
      </form>
      <DraftPreview v-if="draft" :key="`${draftId}-${review.version}`" :id="draftId" :snapshot="draft" :library-id="libraryId" />
      </div>
      <DraftSourceComparison v-if="compareSource && draft" :draft="draft" :library-id="libraryId" :unsaved="dirty || pending" />
      </div>
      <section v-if="!imported && !review.abandoned" class="chapter-confirm" aria-label="章节确认">
        <h3>批量确认本章</h3><p>将导入 {{ review.activeCount }} 题，剩余 {{ review.flaggedCount }} 题待检查；其他章继续保留草稿。</p>
        <button class="primary-button" :disabled="locked || dirty || !review.activeCount" @click="confirmOpen = true; allowFlagged = false">确认本章加入正式题库</button>
        <div v-if="confirmOpen" class="confirm-panel"><p>请核对本章 {{ review.activeCount }} 题，其中 {{ review.flaggedCount }} 题仍有标记。</p>
          <label v-if="review.flaggedCount"><input v-model="allowFlagged" type="checkbox" />明确选择保留待检查标记后导入</label>
          <div class="sync-actions"><button class="primary-button" :disabled="locked || dirty || (review.flaggedCount > 0 && !allowFlagged)" @click="submit('CONFIRM', { allowFlagged })">确认导入 {{ review.activeCount }} 题</button><button class="secondary-button" @click="confirmOpen = false">返回检查</button></div>
        </div>
        <details><summary>放弃本章剩余草稿</summary><p>只排除本章尚未导入的草稿，保留调整记录。其他章和已导入内容继续保留。</p>
          <button class="secondary-button" :disabled="locked || dirty" @click="abandonOpen = true; abandonedChoice = false">放弃剩余草稿…</button>
          <div v-if="abandonOpen" class="confirm-panel"><label><input v-model="abandonedChoice" type="checkbox" />确认放弃本章 {{ review.activeCount }} 题剩余草稿</label>
            <button class="secondary-button" :disabled="locked || !abandonedChoice || !reason.trim()" @click="submit('ABANDON', { confirmed: true, reason })">确认放弃（需填写原因）</button><button class="secondary-button" @click="abandonOpen = false">取消</button></div>
        </details>
      </section>
      <section v-if="imported" aria-label="正式题库"><h3>本章已进入正式题库（{{ review.cards.length }} 题）</h3><RouterLink v-if="!libraryId" :to="`/chapters/${id}/cards`" class="primary-button">查看本章正式题库</RouterLink><p>可进入题库开始主动回忆与评分；在上方修正卡片内容会保留现有学习记录。</p><p v-for="card in review.cards" :key="card.id" class="card-identity">{{ review.items.find(d => d.id === card.draftId)?.question }} · 卡片 ID {{ card.id }}</p></section>
      <details v-if="review.history.length"><summary>调整记录（{{ review.history.length }}）</summary><ol><li v-for="(entry, index) in review.history" :key="index">{{ actions[entry.action] }} · {{ entry.reason }} · 数量 {{ entry.countDelta > 0 ? '+' : '' }}{{ entry.countDelta }}
        <p class="form-note">{{ entry.createdAt }} · 输入草稿 {{ entry.inputIds?.join('、') || '无' }} → 输出草稿 {{ entry.outputIds?.join('、') || '无' }}</p>
      </li></ol></details>
    </template>
  </section>
</template>
