<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { importsApi, stageNames, statusNames, type ImportJob, type Chapter, type DraftPage } from '../api/imports'
import { useSyncStore } from '../stores/sync'
import DraftPreview from '../components/DraftPreview.vue'
const route = useRoute()
const sync = useSyncStore()
const job = ref<ImportJob>()
const chapters = ref<Chapter[]>([])
const chapterId = ref('')
const page = ref<DraftPage>()
const draftId = ref('')
const error = ref('')
const notice = ref('')
const busy = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let stopped = false
let generation = 0
let selection = 0
async function load() {
  if (busy.value || stopped) return
  const current = generation
  busy.value = true; clearTimeout(timer)
  try {
    const result = await importsApi.job(String(route.params.id))
    if (stopped || current !== generation) return
    job.value = result; error.value = ''
    if (result.status === 'READY' && !chapters.value.length) {
      const rows = await importsApi.chapters(result.id)
      if (!stopped && current === generation) chapters.value = rows
    }
  } catch (cause) { if (!stopped && current === generation) error.value = cause instanceof Error ? cause.message : '任务读取失败' }
  finally {
    busy.value = false
    if (!stopped && (current !== generation || error.value || (job.value && ['QUEUED', 'PARSING'].includes(job.value.status)))) timer = setTimeout(() => { void load() }, 2500)
  }
}
async function chooseChapter(id: string, offset = 0) {
  const current = ++selection
  chapterId.value = id; draftId.value = ''; page.value = undefined; error.value = ''
  try { const value = await importsApi.drafts(id, offset); if (!stopped && current === selection) page.value = value }
  catch (cause) { if (!stopped && current === selection) error.value = cause instanceof Error ? cause.message : '草稿读取失败' }
}
async function retry() {
  if (!job.value) return
  error.value = ''; notice.value = ''
  try {
    await sync.enqueue({ kind: 'import.retry', name: 'PDF 解析', jobId: job.value.id, expectedVersion: job.value.version })
    notice.value = '重试请求已在本机保留，请在“保存与同步”查看确认结果。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '重试请求保留失败' }
}
watch(() => sync.state?.revision, () => { void load() })
watch(() => route.params.id, () => { generation++; selection++; job.value = undefined; chapters.value = []; page.value = undefined; chapterId.value = ''; draftId.value = ''; void load() })
onMounted(() => { void load(); window.addEventListener('focus', load) })
onUnmounted(() => { stopped = true; generation++; selection++; clearTimeout(timer); window.removeEventListener('focus', load) })
</script>
<template>
  <RouterLink to="/sources">← 我的资料 / 重新选择 PDF</RouterLink>
  <h1>PDF 解析与草稿</h1>
  <p class="muted">草稿保存在服务端，可退出后继续查看。逐章检查确认后才能进入学习。</p>
  <p v-if="error" role="alert" class="form-error">{{ error }}</p>
  <p v-if="notice" role="status" class="notice">{{ notice }}</p>
  <button class="secondary-button" :disabled="busy" @click="load">{{ busy ? '读取中…' : '刷新任务' }}</button>
  <section v-if="job" class="import-status" aria-label="解析状态">
    <h2>{{ statusNames[job.status] }}</h2>
    <p role="status">{{ stageNames[job.stage] ?? job.stage }} · {{ job.progress }}%</p>
    <progress :value="job.progress" max="100" aria-label="解析进度" />
    <p v-if="job.errorMessage" class="form-error">{{ job.errorMessage }} <small>{{ job.errorCode }}</small></p>
    <button v-if="job.status === 'FAILED'" class="primary-button" :disabled="!sync.state || sync.items.some(i => i.kind === 'import.retry' && i.jobId === job?.id && ['pending', 'syncing'].includes(i.status))" @click="retry">重试解析</button>
    <a :href="`/api/sources/${job.sourceId}/content`" target="_blank" rel="noopener">查看来源 PDF（新窗口）</a>
  </section>
  <template v-if="job?.status === 'READY'">
    <section class="import-report" aria-label="数量报告">
      <h2>章节识别与数量核对</h2>
      <p>{{ job.report.pageCount }} 页 · 目录 {{ job.report.tocCount }} 题 · 草稿 {{ job.report.candidateCount }} 题 · 识别差异 {{ job.report.recognitionDifference }}</p>
      <p>当前资料基线 {{ job.report.expectedCount }} 题，数量差异 {{ job.report.baselineDifference }}。所有候选保留来源，待逐章核对。</p>
      <p v-if="job.report.missingChapters?.length" role="alert">基线章节缺失：{{ job.report.missingChapters.join('、') }}</p>
      <div class="report-scroll"><table><thead><tr><th>章节</th><th>基线</th><th>目录</th><th>草稿</th><th>识别差异</th><th>基线差异</th></tr></thead>
        <tbody><tr v-for="row in job.report.chapters" :key="row.name"><td>{{ row.name }}</td><td>{{ row.expectedCount }}</td><td>{{ row.tocCount }}</td><td>{{ row.candidateCount }}</td><td>{{ row.recognitionDifference }}</td><td>{{ row.baselineDifference }}</td></tr></tbody>
      </table></div>
    </section>
    <section class="chapter-list" aria-label="章节草稿">
      <h2>选择章节查看草稿</h2>
      <div class="chapter-buttons"><button v-for="chapter in chapters" :key="chapter.id" class="secondary-button" :aria-pressed="chapterId === chapter.id" @click="chooseChapter(chapter.id)">
        {{ chapter.module }} / {{ chapter.name }} · {{ chapter.candidateCount }} 题 · {{ chapter.flaggedCount }} 待检查
        <small v-if="chapter.originalName !== chapter.name">（原名 {{ chapter.originalName }}）</small>
      </button></div>
    </section>
    <section v-if="page" class="draft-list" aria-label="候选问题">
      <h2>候选问题（{{ page.total }}）</h2>
      <ol :start="page.offset + 1"><li v-for="draft in page.items" :key="draft.id"><button class="draft-link" :aria-pressed="draftId === draft.id" @click="draftId = draft.id">{{ draft.question }}</button><small> · {{ draft.flags.length }} 处待检查</small></li></ol>
      <div class="sync-actions"><button class="secondary-button" :disabled="page.offset === 0" @click="chooseChapter(chapterId, page.offset - 20)">上一页</button><button class="secondary-button" :disabled="page.offset + page.limit >= page.total" @click="chooseChapter(chapterId, page.offset + 20)">下一页</button></div>
    </section>
    <DraftPreview v-if="draftId" :id="draftId" />
  </template>
</template>
