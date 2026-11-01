<script setup lang="ts">
import type { CardDetail } from '../api/cards'
import { ref } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()
const props = defineProps<{ card: CardDetail; original?: boolean }>()
const imageErrors = ref<number[]>([])
function sourceId(page: number) { return (props.card.sources.find(s => s.page === page) ?? props.card.sources[0])?.sourceId }
</script>
<template>
  <div class="card-answer">
    <section v-for="(block, index) in (original ? card.originalContent as CardDetail['contentBlocks'] : card.contentBlocks)" :key="index" class="source-block">
      <p class="form-note">物理页 {{ block.page }} · {{ original || block.type === 'SOURCE_TEXT' ? 'PDF 原文提取' : '人工排版 / 内容修订（请与原文核对）' }}</p>
      <pre class="source-text">{{ block.textSpans.map(s => s.text).join('\n') }}</pre>
      <figure v-if="block.images.length && sourceId(block.page)">
        <p v-if="imageErrors.includes(index)" role="alert">图片加载失败，请打开来源 PDF 查看。</p>
        <a :href="`/api/sources/${sourceId(block.page)}/pages/${block.page}/image`" target="_blank" rel="noopener" :aria-label="`新窗口查看物理页 ${block.page} 原图，可缩放`"><img class="source-page-image" :src="`/api/sources/${sourceId(block.page)}/pages/${block.page}/image`" :alt="`物理页 ${block.page} 原图，含本题 ${block.images.length} 个图片区域及周边内容`" loading="lazy" @error="imageErrors.push(index)" /></a>
        <figcaption><RouterLink :to="{ path: `/sources/${sourceId(block.page)}/read`, query: { page: block.page, card: card.id, ...(route.path?.startsWith('/practice/') ? { session: String(route.params.id) } : {}) } }">查看图片来源：PDF 物理页 {{ block.page }}</RouterLink>（整页，含周边内容）</figcaption>
      </figure>
    </section>
  </div>
</template>
