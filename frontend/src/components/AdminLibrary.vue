<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { libraryApi, libraryWrite, type LibraryEntry } from '../api/library'
import { request } from '../api/client'
import { statusNames, type ImportJob, type Chapter } from '../api/imports'
import { useSyncStore } from '../stores/sync'
import ChapterReview from './ChapterReview.vue'
const emit = defineEmits<{ dirty: [value: boolean] }>()
const sync = useSyncStore()
const entries = ref<LibraryEntry[]>([]), selected = ref<LibraryEntry>(), preview = ref<LibraryEntry>()
const name = ref(''), description = ref(''), error = ref(''), notice = ref(''), busy = ref(false), dirty = ref(false)
const jobs = ref<ImportJob[]>([]), chapters = ref<Chapter[]>([]), chapter = ref(''), action = ref(''), confirmed = ref(false), rights = ref(false)
const file = ref<File>(), uploadOperation = ref(crypto.randomUUID())
let alive = true, serial = 0, timer: ReturnType<typeof setTimeout> | undefined
async function load() {
  try { const result = await libraryApi.list(); if (alive) entries.value = result }
  catch (e) { if (alive) error.value = e instanceof Error ? e.message : '读取失败' }
}
async function select(id: string) {
  if (dirty.value) { error.value = '请先保存或取消章节修改。'; return }
  const ticket = ++serial; chapter.value = ''; action.value = ''; preview.value = undefined; clearTimeout(timer)
  try {
    const [entry, result] = await Promise.all([libraryApi.preview(id), libraryApi.jobs(id)])
    const list = result[0]?.status === 'READY' ? await libraryApi.chapters(id, result[0].id) : []
    if (!alive || ticket !== serial) return
    selected.value = entry; jobs.value = result; chapters.value = list
    if (result.some(j => ['QUEUED', 'PARSING'].includes(j.status))) timer = setTimeout(() => { if (!dirty.value) void select(id) }, 2500)
  } catch (e) { error.value = e instanceof Error ? e.message : '读取失败'; selected.value = undefined }
}
async function run(work: () => Promise<unknown>) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await work(); notice.value = '服务端已确认。'; await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请刷新核对后重试' }
  finally { busy.value = false }
}
async function create() {
  await run(async () => {
    const result = await libraryWrite<LibraryEntry>('/api/admin/library', { name: name.value, description: description.value })
    name.value = ''; description.value = ''; await select(result.id)
  })
}
function chooseFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0]; uploadOperation.value = crypto.randomUUID() }
async function upload() {
  if (!selected.value || !file.value) return
  const id = selected.value.id, form = new FormData(); form.append('file', file.value)
  await run(async () => {
    await request(`/api/admin/library/${id}/sources?operationId=${uploadOperation.value}`, { method: 'POST', body: form })
    file.value = undefined; await select(id)
  })
}
async function prepare(value: string) {
  if (!selected.value || dirty.value) return
  await run(async () => { preview.value = await libraryApi.preview(selected.value!.id); action.value = value; confirmed.value = false; rights.value = false })
}
async function apply() {
  if (!preview.value || !sync.state) return
  const p = preview.value
  await run(async () => {
    await libraryWrite(`/api/admin/library/${p.id}/transition`, { dataEpoch: sync.state!.dataEpoch, expectedVersion: p.version, expectedRevision: p.revision, action: action.value, confirmed: confirmed.value, rightsConfirmed: rights.value })
    action.value = ''; preview.value = undefined; selected.value = undefined; chapter.value = ''
  })
}
async function retry(job: ImportJob) {
  const id = selected.value!.id
  await run(async () => { await libraryWrite(`/api/admin/library/${id}/jobs/${job.id}/retry`, { expectedVersion: job.version }); await select(id) })
}
async function refreshChapters() {
  await load()
  const id = selected.value?.id, job = jobs.value[0]
  if (!id || !job) return
  try { const result = await libraryApi.chapters(id, job.id); if (alive && selected.value?.id === id) chapters.value = result }
  catch (e) { error.value = e instanceof Error ? e.message : '章节状态读取失败' }
}
function chooseChapter(id: string) { if (!dirty.value) chapter.value = id }
watch(dirty, value => emit('dirty', value))
onBeforeRouteLeave(() => !dirty.value || window.confirm('章节修改尚未保存，确认离开？'))
onMounted(load)
onUnmounted(() => { alive = false; serial++; clearTimeout(timer) })
</script>
<template>
  <section class="admin-box" aria-label="知识库管理">
    <h2>平台知识库</h2><p>每个知识库使用独立的平台内容空间。上传 PDF → 逐章检查 → 确认发布；用户领取后拥有独立副本。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <form class="review-editor" @submit.prevent="create"><h3>新增知识库</h3>
      <label>知识库名称<input v-model="name" maxlength="200" required :disabled="busy" /></label>
      <label>说明<textarea v-model="description" maxlength="4000" :disabled="busy" /></label>
      <button class="primary-button" :disabled="busy || dirty || !name.trim()">新增平台知识库</button>
    </form>
    <button class="secondary-button" :disabled="busy" @click="load">刷新知识库列表</button>
    <ul><li v-for="entry in entries" :key="entry.id"><button class="draft-link" :disabled="busy || dirty || entry.status === 'DELETED'" @click="select(entry.id)">{{ entry.name }}</button> · {{ ({ DRAFT: '待导入/检查', PUBLISHED: '已发布', UNLISTED: '已下架', DELETED: '已删除' }[entry.status]) }} · {{ entry.counts.cards }} 题 / {{ entry.counts.claims }} 人领取</li></ul>
    <section v-if="selected" aria-label="知识库内容维护">
      <h3>{{ selected.name }}</h3><p>{{ selected.description }}</p>
      <form v-if="selected.status !== 'PUBLISHED' && !jobs.length" @submit.prevent="upload"><label>上传来源 PDF（每库一份，最多 50 MiB）<input type="file" accept="application/pdf" :disabled="busy" @change="chooseFile" /></label><button class="primary-button" :disabled="busy || !file">上传并解析</button></form>
      <button class="secondary-button" :disabled="busy || dirty" @click="select(selected.id)">刷新导入状态</button>
      <section v-for="job in jobs" :key="job.id" class="import-status"><h3>{{ statusNames[job.status] }} · {{ job.progress }}%</h3><p v-if="job.errorMessage" role="alert">{{ job.errorMessage }}</p>
        <a :href="`/api/admin/library/${selected.id}/sources/${job.sourceId}/content`" target="_blank" rel="noopener">核对完整来源 PDF</a>
        <button v-if="job.status === 'FAILED'" class="secondary-button" :disabled="busy || selected.status === 'PUBLISHED'" @click="retry(job)">重试解析</button>
        <details v-if="job.status === 'READY'"><summary>解析数量和异常报告</summary><pre class="source-text">{{ JSON.stringify(job.report, null, 2) }}</pre></details>
      </section>
      <div class="chapter-buttons"><button v-for="c in chapters" :key="c.id" class="secondary-button" :disabled="busy || dirty" @click="chooseChapter(c.id)">{{ c.name }} · {{ c.activeCount }} 题 / {{ c.flaggedCount }} 待检查 · {{ c.checkStatus === 'IMPORTED' ? '已确认' : c.abandoned ? '已放弃' : '待确认' }}</button></div>
      <ChapterReview v-if="chapter" :key="`${selected.id}:${chapter}:${selected.status}`" :id="chapter" :library-id="selected.id" :readonly="selected.status === 'PUBLISHED'" @dirty="dirty = $event" @changed="refreshChapters" />
      <div class="sync-actions">
        <button v-if="selected.status !== 'PUBLISHED'" class="primary-button" :disabled="busy || dirty" @click="prepare('PUBLISH')">检查并发布…</button>
        <button v-else class="secondary-button" :disabled="busy || dirty" @click="prepare('UNLIST')">下架…</button>
        <button class="secondary-button" :disabled="busy || dirty" @click="prepare('DELETE')">删除知识库…</button>
      </div>
      <section v-if="preview && action" class="confirm-panel" aria-label="知识库操作确认">
        <h3>{{ ({ PUBLISH: '发布', UNLIST: '下架', DELETE: '删除' }[action]) }}「{{ preview.name }}」</h3>
        <p>{{ preview.counts.chapters }} 章 / {{ preview.counts.cards }} 题 · 在途解析 {{ preview.counts.activeTasks }} 项 · 已领取 {{ preview.counts.claims }} 人</p>
        <p>下架或删除立即停止展示和新领取，已领取副本、笔记和学习进度继续保留。删除不可撤销，来源资源保留至少 7 天后检查引用再清理。</p>
        <p v-if="action === 'PUBLISH'">必须完成全部章节检查；每份来源 PDF 整份提供（含周边原文），请核对授权范围。</p>
        <label v-if="action === 'PUBLISH'"><input v-model="rights" type="checkbox" />确认有权公开提供全部 PDF、图片和整理内容</label>
        <label><input v-model="confirmed" type="checkbox" />确认以上名称、数量和影响范围</label>
        <button class="primary-button" :disabled="busy || !confirmed || (action === 'PUBLISH' && !rights)" @click="apply">确认{{ ({ PUBLISH: '发布', UNLIST: '下架', DELETE: '删除' }[action]) }}</button>
        <button class="secondary-button" :disabled="busy" @click="action = ''">返回</button>
      </section>
    </section>
  </section>
</template>
<style scoped>
form label { display: block; margin: .8rem 0; }
form input:not([type="checkbox"]), form textarea { display: block; width: 100%; max-width: 100%; min-width: 0; min-height: 44px; margin-top: .4rem; padding: .6rem; border: 1px solid #b7c3b7; border-radius: .3rem; font: inherit; background: #fff; }
form textarea { min-height: 88px; resize: vertical; }
.confirm-panel label { display: block; margin: .8rem 0; }
.confirm-panel input[type="checkbox"] { width: 20px; height: 20px; vertical-align: middle; accent-color: #315b48; }
li { overflow-wrap: anywhere; }
</style>
