<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import type { Draft } from '../api/imports'
const PdfReader = defineAsyncComponent(() => import('./PdfReader.vue').then(module => module.default))
const props = defineProps<{ draft: Draft; libraryId?: string; unsaved?: boolean }>()
const rangeIndex = ref(0)
const ranges = computed(() => props.draft.sources.filter(s => Number.isInteger(s.page) && s.page > 0))
const range = computed(() => ranges.value[rangeIndex.value] ?? ranges.value[0])
const bbox = computed(() => {
  const b = range.value?.bbox
  return b?.length === 4 && b.every(Number.isFinite) && b[0]! >= 0 && b[1]! >= 0 && b[2]! > b[0]! && b[3]! > b[1]! ? b : undefined
})
watch(() => props.draft.id, () => { rangeIndex.value = 0 })
watch(ranges, values => { if (rangeIndex.value >= values.length) rangeIndex.value = 0 })
</script>
<template>
  <aside class="source-comparison" aria-label="原文对照">
    <h3>原文对照</h3>
    <p class="form-note">{{ unsaved ? '正在对照本机来源边界；修改尚未获服务端确认。' : '按当前保存的来源范围定位；原 PDF 保持原貌。' }}</p>
    <label v-if="ranges.length">来源段落<select v-model="rangeIndex" aria-label="对照来源段落">
      <option v-for="(source, index) in ranges" :key="index" :value="index">第 {{ index + 1 }} 段 · PDF 物理页 {{ source.page }}</option>
    </select></label>
    <p v-if="range" class="form-note">来源 ID {{ draft.sourceId }} · 范围 {{ range.bbox.join(', ') }}{{ !bbox ? '（边界无效，暂不高亮）' : '' }}</p>
    <PdfReader v-if="range" :key="`${libraryId ?? 'personal'}-${draft.sourceId}`" :source-id="draft.sourceId" :library-id="libraryId" :initial-page="range.page" :bbox="bbox" />
    <p v-else role="status">没有有效来源页，请先修正来源范围。</p>
  </aside>
</template>
