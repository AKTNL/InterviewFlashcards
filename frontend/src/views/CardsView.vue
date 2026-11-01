<script setup lang="ts">
import { onSyncRefresh } from '../sync/refresh'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { cardsApi, filters, ratings, type CatalogEntry, type CardPage } from '../api/cards'
const route = useRoute(), router = useRouter()
const catalog = ref<CatalogEntry[]>([]), result = ref<CardPage>(), error = ref(''), loading = ref(false)
const q = ref(''), deck = ref(''), module = ref(''), chapter = ref(''), filter = ref('ALL')
const decks = computed(() => {
  const values = [...new Map(catalog.value.map(c => [c.deckId, { id: c.deckId, name: c.deckName }])).values()]
  if (deck.value && !values.some(d => d.id === deck.value)) values.push({ id: deck.value, name: '当前题库（暂无可浏览章节，请到题库管理核对）' })
  return values
})
const modules = computed(() => [...new Map(catalog.value.filter(c => !deck.value || c.deckId === deck.value).map(c => [c.moduleId, { id: c.moduleId, name: c.moduleName }])).values()])
const chapters = computed(() => catalog.value.filter(c => (!deck.value || c.deckId === deck.value) && (!module.value || c.moduleId === module.value)))
const scopedCatalog = computed(() => catalog.value.filter(c => !deck.value || c.deckId === deck.value))
let generation = 0
async function load() {
  const current = ++generation; loading.value = true; error.value = ''; result.value = undefined
  q.value = String(route.query.q ?? ''); deck.value = String(route.query.deckId ?? ''); module.value = String(route.query.moduleId ?? ''); chapter.value = String(route.params.id ?? route.query.chapterId ?? ''); filter.value = String(route.query.filter ?? 'ALL')
  const query = new URLSearchParams({ offset: String(route.query.offset ?? 0), limit: '20', filter: filter.value })
  for (const [key, value] of Object.entries({ q: q.value, deckId: deck.value, moduleId: module.value, chapterId: chapter.value })) if (value) query.set(key, value)
  try {
    const [tree, page] = await Promise.all([cardsApi.catalog(), cardsApi.list(query)])
    if (current === generation) { catalog.value = tree; result.value = page }
  } catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '题库读取失败' }
  finally { if (current === generation) loading.value = false }
}
function search(offset = 0) {
  const query: Record<string, string> = { filter: filter.value, offset: String(offset) }
  for (const [key, value] of Object.entries({ q: q.value.trim(), deckId: deck.value, moduleId: module.value, chapterId: chapter.value })) if (value) query[key] = value
  void router.push({ path: '/cards', query })
}
function clear() { q.value = ''; deck.value = ''; module.value = ''; chapter.value = ''; filter.value = 'ALL'; search() }
watch(() => route.fullPath, load, { immediate: true })
onMounted(() => window.addEventListener('focus', load))
onUnmounted(() => { generation++; window.removeEventListener('focus', load) })
onSyncRefresh(load)
</script>
<template>
  <section class="sources-intro"><p class="eyebrow">LIBRARY · 正式题库</p><h1>找一道题，认真读懂</h1><p class="muted">浏览、收藏和整理笔记不会改变复习日期。</p></section>
  <p><RouterLink to="/decks">管理各题库与独立进度</RouterLink> · 已导入 {{ scopedCatalog.filter(c => c.checkStatus === 'IMPORTED').length }} 章 · {{ scopedCatalog.filter(c => c.checkStatus !== 'IMPORTED').length }} 章待确认 <RouterLink to="/sources">继续导入检查</RouterLink></p>
  <form class="library-filters" @submit.prevent="search()">
    <label>题目关键词<input v-model="q" maxlength="200" placeholder="例如 HashMap" /></label>
    <label>题库<select v-model="deck" @change="module = ''; chapter = ''; search()"><option value="">全部题库</option><option v-for="d in decks" :key="d.id" :value="d.id">{{ d.name }} · {{ d.id }}</option></select></label>
    <label>模块<select v-model="module" @change="chapter = ''"><option value="">全部模块</option><option v-for="m in modules" :key="m.id" :value="m.id">{{ m.name }}</option></select></label>
    <label>章节<select v-model="chapter"><option value="">全部章节</option><option v-for="c in chapters" :key="c.chapterId" :value="c.chapterId">{{ c.chapterName }}（{{ c.cardCount }} 题{{ c.checkStatus !== 'IMPORTED' ? '，待确认' : '' }}）</option></select></label>
    <label>状态<select v-model="filter"><option v-for="(label, key) in filters" :key="key" :value="key">{{ label }}</option></select></label>
    <div class="sync-actions"><button class="primary-button">搜索与筛选</button><button type="button" class="secondary-button" @click="clear">清除条件</button><button type="button" class="secondary-button" @click="load">刷新题库</button></div>
  </form>
  <p v-if="error" role="alert" class="form-error">{{ error }} <button class="secondary-button" @click="load">重试</button></p>
  <p v-if="loading" role="status">正在读取题库…</p>
  <section v-if="result" class="library-results" aria-label="题库结果">
    <p v-if="filter === 'DUPLICATE'" class="notice">按标题忽略空白及大小写查找本账号内的重复题，匹配范围包含其他题库。答案和学习进度各自独立，请对照来源后再决定如何整理。</p>
    <p>找到 {{ result.total }} 题 <small class="muted">· 学习日 {{ result.learningDate }}（上海时区）</small></p>
    <RouterLink v-if="result.items.length" class="primary-button" :to="{ path: '/practice', query: { cards: result.items.map(c => c.id).join(',') } }">练习本页 {{ result.items.length }} 题</RouterLink>
    <div v-if="!result.items.length" class="empty-state"><p>{{ catalog.length ? '没有符合条件的题目。可以清除条件，或先确认导入章节。' : '还没有题库，从上传 PDF 并逐章检查开始。' }}</p><div class="sync-actions"><button v-if="catalog.length" class="secondary-button" @click="clear">清除搜索与筛选</button><RouterLink to="/sources">上传或继续检查章节</RouterLink></div></div>
    <ol :start="result.offset + 1"><li v-for="card in result.items" :key="card.id">
      <RouterLink :to="`/cards/${card.id}`">{{ card.question }}</RouterLink>
      <p class="form-note"><RouterLink :to="{ path: '/cards', query: { deckId: card.deckId } }">{{ card.deckName }} · {{ card.deckId }}</RouterLink> / {{ card.moduleName }} / {{ card.chapterName }} · {{ card.learned ? '已学' : '未学' }}{{ card.favorite ? ' · 已收藏' : '' }}{{ card.latestRating ? ` · 最近${ratings[card.latestRating]}` : '' }}{{ card.dueDate ? ` · 复习日期 ${card.dueDate}` : '' }}</p>
      <p v-if="card.needsCheck" class="check-reminder">待检查：请核对完整答案及来源</p>
    </li></ol>
    <nav class="sync-actions" aria-label="题库分页"><button class="secondary-button" :disabled="result.offset === 0" @click="search(Math.max(0, result.offset - result.limit))">上一页</button><span>第 {{ Math.floor(result.offset / result.limit) + 1 }} 页</span><button class="secondary-button" :disabled="result.offset + result.limit >= result.total" @click="search(result.offset + result.limit)">下一页</button></nav>
  </section>
</template>
