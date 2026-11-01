<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { cardsApi, type DeckOverview } from '../api/cards'
import { onSyncRefresh } from '../sync/refresh'
const overview = ref<DeckOverview>(), q = ref(''), loading = ref(false), error = ref('')
const decks = computed(() => overview.value?.decks.filter(d => `${d.name} ${d.id} ${overview.value?.sources.filter(s => s.deckId === d.id).map(s => s.name).join(' ')}`.toLocaleLowerCase().includes(q.value.trim().toLocaleLowerCase())) ?? [])
let generation = 0
async function load() {
  const current = ++generation; loading.value = true; error.value = ''; overview.value = undefined
  try { const value = await cardsApi.decks(); if (current === generation) overview.value = value }
  catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '读取题库失败' }
  finally { if (current === generation) loading.value = false }
}
onMounted(() => { void load(); window.addEventListener('focus', load) })
onUnmounted(() => { generation++; window.removeEventListener('focus', load) })
onSyncRefresh(load, ['deck', 'module', 'chapter', 'card', 'review_state', 'source_document', 'import_job', 'learning_preference_chapter'])
const scope = (id: string, filter = 'ALL') => ({ path: '/cards', query: { deckId: id, filter } })
</script>
<template>
  <section class="sources-intro"><p class="eyebrow">DECKS · 题库管理</p><h1>每份题库，各自积累</h1><p>同名题目和相同 PDF 副本各自保留卡片、笔记与学习进度。切换浏览不会改变每日学习范围。</p></section>
  <div class="sync-actions"><RouterLink to="/cards">浏览全部题目</RouterLink><RouterLink :to="{ path: '/cards', query: { filter: 'DUPLICATE' } }">核对重复题</RouterLink><RouterLink to="/sources">导入资料</RouterLink><button class="secondary-button" :disabled="loading" @click="load">刷新题库</button></div>
  <p v-if="error" role="alert" class="form-error">{{ error }} <button class="secondary-button" @click="load">重试</button></p>
  <p v-if="loading" role="status">正在读取各题库进度…</p>
  <template v-if="overview">
    <p>共 {{ overview.decks.length }} 个题库 · 学习日 {{ overview.learningDate }}（上海时区）。到期与薄弱包含暂停章节；每日任务仅使用学习设置中的范围。</p>
    <label class="deck-search">查找题库<input v-model="q" type="search" placeholder="题库名称、来源文件或完整题库 ID" /></label>
    <p class="form-note">重复题仅按标题忽略空白及大小写提示，答案可能不同。请逐题对照来源；如需清理完整副本，先备份，再读取该题库的删除影响。</p>
    <p v-if="!overview.decks.length" class="empty-state">还没有题库，请上传 PDF 并检查章节，或从平台知识库领取。</p>
    <p v-else-if="!decks.length" role="status">没有符合条件的题库。</p>
    <section v-for="d in decks" :key="d.id" class="chapter-review deck-summary" :aria-label="`题库：${d.name}`">
      <h2><RouterLink :to="scope(d.id)">{{ d.name }}</RouterLink></h2><p class="form-note">题库 ID：{{ d.id }}</p>
      <p>已确认 {{ d.imported }}/{{ d.chapters }} 章 · 参与每日学习 {{ d.selected }} 章</p>
      <p>已学 {{ d.learned }}/{{ d.total }} 题 · 未学 {{ d.total - d.learned }} · 到期 {{ d.due }} · 薄弱 {{ d.weak }}</p>
      <div class="sync-actions"><RouterLink :to="scope(d.id)">全部题目</RouterLink><RouterLink :to="scope(d.id, 'UNLEARNED')">未学</RouterLink><RouterLink :to="scope(d.id, 'DUE')">到期</RouterLink><RouterLink :to="scope(d.id, 'WEAK')">薄弱</RouterLink><RouterLink v-if="d.duplicates" :to="scope(d.id, 'DUPLICATE')">重复题提示 {{ d.duplicates }} 题</RouterLink></div>
      <ul><li v-for="s in overview.sources.filter(s => s.deckId === d.id)" :key="s.id">
        {{ s.name }} · {{ s.sharedCopy ? '领取副本' : '自行上传' }} · <RouterLink :to="`/imports/${s.jobId}`">查看导入与章节</RouterLink>
        <p v-if="s.sameFileDecks" class="notice">与另外 {{ s.sameFileDecks }} 个题库的来源文件相同（SHA-256 一致），学习进度独立。</p>
      </li></ul>
      <div class="sync-actions"><RouterLink :to="{ path: '/settings', query: { deckId: d.id } }">设置本题库学习范围</RouterLink><RouterLink :to="{ path: '/data', query: { deckId: d.id } }">删除或重置此题库</RouterLink></div>
    </section>
  </template>
</template>
<style scoped>
.deck-search { display: grid; gap: .4rem; max-width: 32rem; }
.deck-search input { width: 100%; min-width: 0; min-height: 44px; padding: .6rem; font: inherit; border: 1px solid #b7c3b7; border-radius: .3rem; background: #fff; }
.deck-summary { padding: clamp(16px, 3vw, 28px); border: 1px solid #d8e0d4; border-radius: 10px; background: #fafbf8; }
.deck-summary h2 { margin-top: 0; }
</style>
