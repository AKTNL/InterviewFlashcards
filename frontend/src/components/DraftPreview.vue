<script setup lang="ts">
import { ref, watch } from 'vue'
import { importsApi, type Draft } from '../api/imports'
const props = defineProps<{ id: string }>()
const draft = ref<Draft>()
const error = ref('')
const opened = ref<number[]>([])
const imageErrors = ref<number[]>([])
watch(() => props.id, async (id, _, onCleanup) => {
  let stale = false; onCleanup(() => { stale = true })
  draft.value = undefined; error.value = ''; opened.value = []; imageErrors.value = []
  try { const value = await importsApi.draft(id); if (!stale) draft.value = value }
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
      <p class="eyebrow">{{ draft.sourceEntry }} · 未确认草稿</p><h2>{{ draft.question }}</h2>
      <details class="check-flags"><summary>待检查原因（{{ draft.flags.length }} 处）</summary>
        <ul><li v-for="(flag, i) in draft.flags" :key="i">{{ flag.page ? `第 ${flag.page} 页：` : '' }}{{ flag.reason }}</li></ul>
      </details>
      <p class="muted">以下为完整提取原文。代码、表格和图片请展开对应页面原图核对。</p>
      <section v-for="block in draft.contentBlocks" :key="block.page" class="source-block">
        <h3>物理页 {{ block.page }}</h3>
        <pre class="source-text">{{ block.textSpans.map(s => s.text).join('\n') }}</pre>
        <p v-if="block.images.length">本页含 {{ block.images.length }} 个图片区域，保留在页面原图中。</p>
        <details @toggle="showImage($event, block.page)"><summary>查看第 {{ block.page }} 页原图（整页，含周边内容）</summary>
          <p v-if="imageErrors.includes(block.page)" role="alert">原图加载失败，可通过下方链接打开 PDF。</p>
          <img v-if="opened.includes(block.page)" class="source-page-image" :src="`/api/sources/${draft.sourceId}/pages/${block.page}/image`" :alt="`PDF 物理页 ${block.page} 原图`" loading="lazy" @error="imageErrors.push(block.page)" />
        </details>
        <a :href="`/api/sources/${draft.sourceId}/content#page=${block.page}`" target="_blank" rel="noopener">打开 PDF 第 {{ block.page }} 页（新窗口）</a>
        <p class="form-note">本题页内范围（左上角坐标）：{{ draft.sources.find(s => s.page === block.page)?.bbox.map(n => n.toFixed(1)).join(', ') }}</p>
      </section>
      <details v-if="draft.followups.length"><summary>追加追问（{{ draft.followups.length }}）</summary>
        <div v-for="(followup, i) in draft.followups" :key="i"><h3>{{ followup.title }} · 第 {{ followup.page }} 页</h3><pre class="source-text">{{ followup.text }}</pre></div>
      </details>
    </template>
  </article>
</template>
