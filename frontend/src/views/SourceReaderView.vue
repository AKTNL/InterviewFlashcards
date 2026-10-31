<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PdfReader from '../components/PdfReader.vue'
const route = useRoute()
const page = computed(() => Math.max(1, Math.trunc(Number(route.query.page)) || 1))
const bbox = computed(() => {
  const values = String(route.query.bbox ?? '').split(',').map(Number)
  return values.length === 4 && values.every(v => Number.isFinite(v) && v >= 0) && values[2]! > values[0]! && values[3]! > values[1]! ? values : undefined
})
const back = computed(() => typeof route.query.card === 'string' && /^[\da-f-]{36}$/i.test(route.query.card) ? `/cards/${route.query.card}` : '/cards')
</script>
<template><RouterLink :to="back">← 返回卡片题库</RouterLink><h1 class="reader-title">来源 PDF · 原文核对</h1><PdfReader :source-id="String(route.params.id)" :initial-page="page" :bbox="bbox" /></template>
