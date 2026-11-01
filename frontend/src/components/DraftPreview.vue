<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { libraryReviewApi } from '../api/library'
import { importsApi, type Draft } from '../api/imports'
const props = defineProps<{ id: string; card?: Draft; snapshot?: Draft; libraryId?: string }>()
const sourceBase = computed(() => props.libraryId ? `/api/admin/library/${props.libraryId}/sources` : '/api/sources')
const draft = ref<Draft>()
const error = ref('')
const opened = ref<number[]>([])
const imageErrors = ref<number[]>([])
watch(() => [props.id, props.card, props.snapshot] as const, async ([id, card, snapshot], _, onCleanup) => {
  let stale = false; onCleanup(() => { stale = true })
  draft.value = undefined; error.value = ''; opened.value = []; imageErrors.value = []
  try { const value = snapshot ?? card ?? await (props.libraryId ? libraryReviewApi(props.libraryId) : importsApi).draft(id); if (!stale) draft.value = value }
  catch (cause) { if (!stale) error.value = cause instanceof Error ? cause.message : '草稿读取失败' }
}, { immediate: true })
function showImage(event: Event, page: number) {
  if ((event.target as HTMLDetailsElement).open && !opened.value.includes(page)) opened.value.push(page)
}
</script>
<template>
  <article class="draft-preview" aria-label="草稿全文">
    <p v-if="error" role="alert" class="form-error">{{ error }}，请重新选择此题以重试。</p>
    <p v-else-if="!draft" role="status">正在读取完整草稿…</p>
    <template v-else>
      <p class="eyebrow">{{ draft.sourceEntry }} · {{ card ? '正式卡片' : '来源草稿' }}{{ draft.excluded ? ' · 已排除' : '' }}</p><h2>{{ draft.question }}</h2>
      <p class="form-note">{{ card ? '卡片 ID' : '草稿 ID' }} {{ draft.id }} · 版本 {{ draft.version }} · 来源 ID {{ draft.sourceId }}</p>
      <p v-if="draft.parentDraftId" class="form-note">此题由原问题人工拆分，来源与调整记录保留关联。</p>
      <details class="check-flags"><summary>待检查原因（{{ draft.flags.length }} 处）</summary>
        <ul><li v-for="(flag, i) in draft.flags" :key="i">{{ flag.page ? `第 ${flag.page} 页：` : '' }}{{ flag.reason }}</li></ul>
      </details>
      <p class="muted">以下为当前完整展示内容。人工修订单独标明，代码、表格和图片请展开对应页面原图核对。</p>
      <details v-if="draft.originalContent"><summary>原始提取证据（保留未覆盖）</summary><pre class="source-text">{{ JSON.stringify(draft.originalContent, null, 2) }}</pre></details>
      <details><summary>全部来源范围（{{ draft.sources.length }} 段）</summary><ul><li v-for="(source, i) in draft.sources" :key="i"><a :href="`${sourceBase}/${draft.sourceId}/content#page=${source.page}`" target="_blank" rel="noopener">PDF 第 {{ source.page }} 页</a> · {{ source.bbox.map(n => n.toFixed(1)).join(', ') }}</li></ul></details>
      <section v-for="(block, index) in draft.contentBlocks" :key="index" class="source-block">
        <h3>物理页 {{ block.page }}</h3>
        <p v-if="block.type === 'MANUAL_TEXT'" class="notice">人工展示修订，请对照原始提取证据及 PDF 核对。</p>
        <pre class="source-text">{{ block.textSpans.map(s => s.text).join('\n') }}</pre>
        <p v-if="block.images.length">本页含 {{ block.images.length }} 个图片区域，保留在页面原图中。</p>
        <details @toggle="showImage($event, block.page)"><summary>查看第 {{ block.page }} 页原图（整页，含周边内容）</summary>
          <p v-if="imageErrors.includes(block.page)" role="alert">原图加载失败，可通过下方链接打开 PDF。</p>
          <img v-if="opened.includes(block.page)" class="source-page-image" :src="`${sourceBase}/${draft.sourceId}/pages/${block.page}/image`" :alt="`PDF 物理页 ${block.page} 原图`" loading="lazy" @error="imageErrors.push(block.page)" />
        </details>
        <a :href="`${sourceBase}/${draft.sourceId}/content#page=${block.page}`" target="_blank" rel="noopener">打开 PDF 第 {{ block.page }} 页（新窗口）</a>
        <p class="form-note">本题页内范围（左上角坐标）：{{ draft.sources.find(s => s.page === block.page)?.bbox.map(n => n.toFixed(1)).join(', ') }}</p>
      </section>
      <details v-if="draft.followups.length"><summary>追加追问（{{ draft.followups.length }}）</summary>
        <div v-for="(followup, i) in draft.followups" :key="i"><h3>{{ followup.title }} · 第 {{ followup.page }} 页</h3><pre class="source-text">{{ followup.text }}</pre></div>
      </details>
    </template>
  </article>
</template>
