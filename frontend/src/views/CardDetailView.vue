<script setup lang="ts">
import { onSyncRefresh } from '../sync/refresh'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { cardsApi, ratings, type CardDetail } from '../api/cards'
import { importsApi, type Draft } from '../api/imports'
import { useSyncStore } from '../stores/sync'
import CardAnswer from '../components/CardAnswer.vue'
import ReadingPane from '../components/ReadingPane.vue'
import DraftFields from '../components/DraftFields.vue'
const route = useRoute(), sync = useSyncStore()
const card = ref<CardDetail>(), error = ref(''), message = ref(''), loading = ref(false), answer = ref(false), hint = ref(false)
const editor = ref(''), form = ref({ notes: '', hint: '', memoryPoints: '', favorite: false, technicalCorrection: '', applicableVersion: '', tags: '' })
const draft = ref<Draft>(), reason = ref(''), baseline = ref('')
const saving = ref(false)
const dirty = computed(() => !!editor.value && baseline.value !== JSON.stringify({ form: form.value, draft: draft.value, reason: reason.value }))
const outstanding = computed(() => sync.items.some(i => i.status !== 'synced' && ((i.kind === 'card.update' && i.cardId === card.value?.id) || (i.kind === 'chapter.review' && i.chapterId === card.value?.chapterId))))
let generation = 0
async function load() {
  if (editor.value || saving.value) return
  const current = ++generation; const id = String(route.params.id); loading.value = true; error.value = ''
  try { const value = await cardsApi.detail(id); if (current === generation) card.value = value }
  catch (cause) { if (current === generation) { card.value = undefined; error.value = cause instanceof Error ? cause.message : '卡片读取失败' } }
  finally { if (current === generation) loading.value = false }
}
async function edit(mode: string) {
  if (!card.value) return
  error.value = ''; message.value = ''; loading.value = true
  const current = ++generation
  try {
    const value = mode === 'CONTENT' ? await importsApi.draft(card.value.draftId) : undefined
    if (current !== generation) return
    draft.value = value; reason.value = ''
    form.value = { ...card.value.annotation, technicalCorrection: card.value.technicalCorrection, applicableVersion: card.value.applicableVersion ?? '', tags: card.value.tags.join('，') }
    baseline.value = JSON.stringify({ form: form.value, draft: draft.value, reason: reason.value }); editor.value = mode
  } catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '读取编辑内容失败' }
  finally { if (current === generation) loading.value = false }
}
function canLeave() { return !dirty.value || window.confirm('还有未保存的编辑，确定离开并放弃吗？') }
function cancel() { if (canLeave()) { editor.value = ''; void load() } }
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
async function save(favorite?: boolean) {
  const c = card.value; if (!c || saving.value || outstanding.value) return
  saving.value = true; error.value = ''
  try {
    if (editor.value === 'CONTENT') {
      if (!reason.value.trim()) throw new Error('请填写本次调整原因')
      const d = draft.value!
      await sync.enqueue({ kind: 'chapter.review', name: c.question, chapterId: c.chapterId, action: 'EDIT', expectedVersion: c.chapterVersion,
        payload: { draftId: c.draftId, reason: reason.value, value: { question: d.question, contentBlocks: d.contentBlocks, sources: d.sources, flags: d.flags, followups: d.followups } } })
    } else {
      const mode = editor.value || 'ANNOTATION'
      const payload = mode === 'METADATA' ? { technicalCorrection: form.value.technicalCorrection, applicableVersion: form.value.applicableVersion.trim() || null,
        tags: form.value.tags.split(/[，,\n]/).map(t => t.trim()).filter(Boolean) }
        : { notes: editor.value ? form.value.notes : c.annotation.notes, hint: editor.value ? form.value.hint : c.annotation.hint,
          memoryPoints: editor.value ? form.value.memoryPoints : c.annotation.memoryPoints, favorite: favorite ?? form.value.favorite }
      await sync.enqueue({ kind: 'card.update', name: c.question, cardId: c.id, action: mode, payload,
        expectedVersion: mode === 'ANNOTATION' ? c.annotation.version : c.version })
    }
    editor.value = ''; message.value = '编辑已保留，请在“保存与同步”查看服务端确认结果。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败，请重试' }
  finally { saving.value = false; if (!editor.value) void load() }
}
watch(() => route.params.id, () => { generation++; card.value = undefined; editor.value = ''; answer.value = false; hint.value = false; message.value = ''; void load() }, { immediate: true })
watch(() => sync.items.filter(i => i.status === 'synced').map(i => i.operationId).join(','), () => { void load() })
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)
onMounted(() => { window.addEventListener('focus', load); window.addEventListener('beforeunload', beforeUnload) })
onUnmounted(() => { generation++; window.removeEventListener('focus', load); window.removeEventListener('beforeunload', beforeUnload) })
onSyncRefresh(load)
</script>
<template>
  <RouterLink to="/cards">← 返回题库</RouterLink>
  <p v-if="error" role="alert" class="form-error">{{ error }} <button class="secondary-button" @click="load">重试读取</button></p>
  <p v-if="loading" role="status">正在读取卡片…</p>
  <ReadingPane v-if="card" :key="card.id" v-model:answer="answer" v-model:hint="hint" :position-key="`card:${card.id}:${card.displayRevision}`" :enabled="!editor && !loading && !saving && !error">
  <article class="card-detail chapter-review">
    <p class="eyebrow">{{ card.deckName }} / {{ card.moduleName }} / {{ card.chapterName }}</p><h1>{{ card.question }}</h1>
    <RouterLink :to="{ path: '/practice', query: { cards: card.id } }" class="primary-button">主动回忆与评分</RouterLink>
    <p v-if="card.needsCheck" role="note" class="check-reminder">待检查：此卡片仍需对照原文核对。</p>
    <details v-if="card.needsCheck" data-reading-detail><summary>待检查原因（{{ card.flags.length }} 处）</summary><ul><li v-for="(flag, i) in card.flags" :key="i">{{ flag.reason }}</li></ul></details>
    <p v-if="card.parentCardId" class="form-note">由关联主问题人工拆分：<RouterLink :to="`/cards/${card.parentCardId}`">查看原卡片</RouterLink></p>
    <p>适用版本：{{ card.applicableVersion || '未知（未填写）' }} <span v-for="tag in card.tags" :key="tag" class="card-tag">{{ tag }}</span></p>
    <p class="form-note">{{ card.review.learned ? '已学' : '未学' }}{{ card.review.latestRating ? ` · 最近${ratings[card.review.latestRating]}` : '' }}{{ card.review.dueDate ? ` · 复习日期 ${card.review.dueDate}` : '' }}{{ card.review.lapseCount !== null ? ` · 遗忘 ${card.review.lapseCount} 次` : '' }} · 仅浏览不计入学习</p>
    <div class="sync-actions"><button class="primary-button" :aria-expanded="answer" @click="answer = !answer">{{ answer ? '收起完整答案' : '展开完整答案' }}</button><button class="secondary-button" :aria-expanded="hint" @click="hint = !hint">{{ hint ? '收起提示与要点' : '查看提示与要点' }}</button><button class="secondary-button" :disabled="!!editor || saving || outstanding" :aria-pressed="card.annotation.favorite" @click="save(!card.annotation.favorite)">{{ card.annotation.favorite ? '取消收藏' : '收藏题目' }}</button></div>
    <section v-if="hint" aria-label="回忆提示与要点"><h2>个人回忆提示与要点</h2><p class="prose-text">{{ card.annotation.hint || '尚未填写提示' }}</p><p class="prose-text">{{ card.annotation.memoryPoints || '尚未填写记忆要点' }}</p><p class="form-note">使用提示后，请按本次独立回忆的程度评价自己。</p></section>
    <section v-if="answer" class="draft-preview" aria-label="完整答案">
      <h2>完整答案</h2><CardAnswer :key="card.id" :card="card" />
      <aside v-if="card.technicalCorrection" class="technical-correction"><h3>个人技术纠错（人工补充）</h3><p class="prose-text">{{ card.technicalCorrection }}</p><p class="form-note">此内容由个人整理，请与下方 PDF 原文核对。</p></aside>
      <details data-reading-detail><summary>核对原始提取内容（保留未覆盖）</summary><CardAnswer :card="card" original /></details>
    </section>
    <details v-for="(followup, i) in card.followups" :key="`${card.id}-${i}`" class="followup" data-reading-detail><summary>追问 {{ i + 1 }}：{{ followup.title }}</summary><pre class="source-text">{{ followup.text }}</pre><p class="form-note">来源：物理页 {{ followup.page }}</p></details>
    <section class="card-sources" aria-label="全部来源范围"><h2>原文核对 · {{ card.sources.length }} 段来源</h2><ol><li v-for="(source, i) in card.sources" :key="i"><RouterLink :to="{ path: `/sources/${source.sourceId}/read`, query: { page: source.page, bbox: source.bbox.join(','), card: card.id } }">{{ source.name }} · 物理页 {{ source.page }} · 范围 {{ i + 1 }}</RouterLink><small class="form-note">（左上角坐标 {{ source.bbox.map(n => n.toFixed(1)).join(', ') }}）</small></li></ol></section>
    <section aria-label="个人笔记"><h2>个人笔记</h2><p class="prose-text">{{ card.annotation.notes || '记下自己的表达、例子和容易遗漏的地方。' }}</p></section>
    <p v-if="message" role="status">{{ message }}</p><p v-if="outstanding" class="notice">此卡片有待同步或待处理的编辑，请先在“保存与同步”核对。</p>
    <div v-if="!editor" class="sync-actions"><button class="secondary-button" :disabled="outstanding || loading" @click="edit('ANNOTATION')">编辑笔记、提示与要点</button><button class="secondary-button" :disabled="outstanding || loading" @click="edit('METADATA')">编辑技术纠错、版本与标签</button><button class="secondary-button" :disabled="outstanding || loading" @click="edit('CONTENT')">修正内容与待检查标记</button><button class="secondary-button" @click="load">读取最新卡片</button></div>
    <form v-if="editor" class="review-editor" @submit.prevent="save()">
      <p v-if="dirty" role="status">有未保存的编辑，请保存后再离开。</p>
      <h2>{{ editor === 'ANNOTATION' ? '个人整理' : editor === 'METADATA' ? '个人技术纠错与分类' : '人工排版 / 内容修订' }}</h2>
      <fieldset :disabled="saving || outstanding">
        <template v-if="editor === 'ANNOTATION'"><label>个人笔记<textarea v-model="form.notes" maxlength="50000" rows="6" /></label><label>回忆提示<textarea v-model="form.hint" maxlength="10000" rows="3" /></label><label>记忆要点<textarea v-model="form.memoryPoints" maxlength="20000" rows="4" /></label><label><input v-model="form.favorite" type="checkbox" />收藏此题</label></template>
        <template v-else-if="editor === 'METADATA'"><label>个人技术纠错<textarea v-model="form.technicalCorrection" maxlength="50000" rows="6" /></label><label>适用版本（未知请留空）<input v-model="form.applicableVersion" maxlength="200" /></label><label>标签（以逗号分隔，最多 30 个）<input v-model="form.tags" /></label><p class="form-note">技术纠错单独标明，原文内容和学习进度保留。</p></template>
        <template v-else-if="draft"><p>原始提取证据保留，保存后将标明为人工修订。若仅补充技术观点，请使用技术纠错。</p><DraftFields v-model="draft" prefix="card-edit" /><label>本次调整原因<input v-model="reason" maxlength="2000" required /></label></template>
      </fieldset>
      <div class="sync-actions"><button class="primary-button" :disabled="saving || outstanding || !dirty">保存编辑</button><button type="button" class="secondary-button" :disabled="saving" @click="cancel">取消编辑</button></div>
    </form>
  </article>
  </ReadingPane>
</template>
