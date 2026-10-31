<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { importsApi, type Draft } from '../api/imports'
import DraftPreview from '../components/DraftPreview.vue'
const route = useRoute()
const cards = ref<Draft[]>([])
const selected = ref<Draft>()
const error = ref('')
let generation = 0
async function load() {
  const current = ++generation
  try {
    const value = await importsApi.cards(String(route.params.id))
    if (current !== generation) return
    cards.value = value; selected.value = value.find(c => c.id === selected.value?.id) ?? value[0]; error.value = ''
  } catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '题库读取失败' }
}
watch(() => route.params.id, () => { selected.value = undefined; cards.value = []; void load() })
onMounted(() => { void load(); window.addEventListener('focus', load) })
onUnmounted(() => { generation++; window.removeEventListener('focus', load) })
</script>
<template>
  <RouterLink to="/sources">← 我的资料 / 继续其他章节检查</RouterLink><h1>本章正式题库</h1>
  <p>已确认 {{ cards.length }} 题；仍有标记的内容需要继续核对。</p><button class="secondary-button" @click="load">刷新正式题库</button>
  <p v-if="error" role="alert" class="form-error">{{ error }}</p>
  <section class="draft-list"><ol><li v-for="card in cards" :key="card.id"><button class="draft-link" :aria-pressed="selected?.id === card.id" @click="selected = card">{{ card.question }}</button><small> · {{ card.flags.length }} 处待检查</small></li></ol></section>
  <DraftPreview v-if="selected" :id="selected.id" :card="selected" />
  <p v-if="!cards.length && !error">本章尚未确认，先在导入页逐章检查并确认。</p>
</template>
