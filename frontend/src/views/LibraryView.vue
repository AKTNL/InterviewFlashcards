<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { libraryApi, libraryWrite, type LibraryEntry, type LibraryClaim } from '../api/library'
import { useAuthStore } from '../stores/auth'
import { useSyncStore } from '../stores/sync'
const auth = useAuthStore(), sync = useSyncStore()
const entries = ref<LibraryEntry[]>([]), claims = ref<LibraryClaim[]>([]), page = ref(0), selected = ref<LibraryEntry>()
const error = ref(''), notice = ref(''), confirmed = ref(false), busy = ref(false)
let alive = true, serial = 0
async function load() {
  const ticket = ++serial; error.value = ''; selected.value = undefined
  try {
    const [list, received] = await Promise.all([libraryApi.catalog(page.value), auth.user ? libraryApi.claims() : Promise.resolve([])])
    if (alive && ticket === serial) { entries.value = list; claims.value = received }
  } catch (e) { if (alive && ticket === serial) { entries.value = []; error.value = e instanceof Error ? e.message : '目录读取失败' } }
}
async function claim() {
  if (!selected.value || !sync.state || busy.value) return
  busy.value = true; error.value = ''
  try {
    await libraryWrite(`/api/library/${selected.value.id}/claim`, { dataEpoch: sync.state.dataEpoch, contentVersion: selected.value.content_version, confirmed: confirmed.value })
    notice.value = '领取成功。请到学习设置选择新增章节；副本将独立保留。'; await sync.refresh(); await load()
  } catch (e) { error.value = e instanceof Error ? e.message : '领取失败，请刷新核对后重试' }
  finally { busy.value = false }
}
watch(() => auth.user?.id, load)
onMounted(load); onUnmounted(() => { alive = false; serial++ })
</script>
<template>
  <h1>平台知识库</h1><p>预览已发布题库，登录并确认后领取个人副本。后续平台更新、下架和删除均不改变已领取内容与学习进度。</p>
  <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
  <button class="secondary-button" :disabled="busy" @click="load">刷新目录</button>
  <p v-if="!entries.length && !error">当前没有已发布的知识库。</p>
  <article v-for="entry in entries" :key="entry.id" class="admin-box">
    <h2>{{ entry.name }}</h2><p>{{ entry.description }}</p><p>内容版本 {{ entry.content_version }} · {{ entry.manifest.counts.card }} 题 / {{ entry.manifest.counts.chapter }} 章</p>
    <details><summary>预览章节与来源范围</summary><p>{{ entry.manifest.chapters.join('、') }}</p><ul><li v-for="source in entry.manifest.sources" :key="source.sha256">{{ source.name }} · 完整 PDF 第 {{ source.includedPages }} 页（共 {{ source.pageCount }} 页）</li></ul></details>
    <details v-if="entry.samples?.length"><summary>预览已确认内容（前 {{ entry.samples.length }} 题）</summary><section v-for="(sample, index) in entry.samples" :key="index"><h3>{{ sample.question }}</h3><pre class="source-text">{{ sample.answer }}</pre></section></details>
    <p v-if="claims.some(c => c.library_id === entry.id)">已领取，个人副本保持独立。<RouterLink to="/cards">查看我的题库</RouterLink></p>
    <button v-else-if="auth.user" class="primary-button" :disabled="busy || !sync.state" @click="selected = entry; confirmed = false">领取独立副本…</button>
    <RouterLink v-else to="/login">登录后领取</RouterLink>
  </article>
  <section v-if="selected" class="confirm-panel" aria-label="领取知识库确认"><h2>领取「{{ selected.name }}」</h2>
    <p>包含上述全部来源 PDF 和图片，计入个人存储配额。每个账号每库领取一次；没有笔记、收藏或学习历史，需自行选择学习范围。</p>
    <label><input v-model="confirmed" type="checkbox" :disabled="busy" />已核对完整来源范围，确认领取个人副本</label>
    <button class="primary-button" :disabled="busy || !confirmed" @click="claim">确认领取</button><button class="secondary-button" :disabled="busy" @click="selected = undefined">取消</button>
  </section>
  <div class="sync-actions"><button class="secondary-button" :disabled="busy || page === 0" @click="page--; load()">上一页</button><span>第 {{ page + 1 }} 页</span><button class="secondary-button" :disabled="busy || entries.length < 20" @click="page++; load()">下一页</button></div>
</template>
