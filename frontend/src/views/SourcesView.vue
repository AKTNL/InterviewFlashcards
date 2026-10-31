<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError } from '../api/client'
import { useSyncStore } from '../stores/sync'
import type { Source } from '../sync/types'
import SourceImport from '../components/SourceImport.vue'
const sync = useSyncStore()
const sources = computed(() => sync.state?.sources ?? [])
const loading = computed(() => sync.busy)
const uploading = ref(false)
const error = ref<ApiError | null>(null)
const notice = ref('')
const input = ref<HTMLInputElement>()
const editing = ref<{ id: string; name: string; version: number } | null>(null)
function fail(cause: unknown) {
  error.value = cause instanceof ApiError ? cause : new ApiError('UNKNOWN', '操作失败，请重试', 0)
}
async function load() {
  error.value = null
  await sync.refresh(true)
}
function edit(source: Source) { editing.value = { id: source.id, name: source.originalFilename, version: source.version } }
async function saveName() {
  const value = editing.value
  if (!value) return
  error.value = null
  try {
    await sync.enqueue({ kind: 'source.rename', sourceId: value.id, name: value.name, expectedVersion: value.version })
    editing.value = null
  } catch (cause) { fail(cause) }
}
async function upload() {
  const file = input.value?.files?.[0]
  if (!file || uploading.value) return
  error.value = null
  notice.value = ''
  if (file.size > 50 * 1024 * 1024) {
    error.value = new ApiError('FILE_TOO_LARGE', '文件不能超过 50 MiB', 413)
    if (input.value) input.value.value = ''
    return
  }
  uploading.value = true
  try {
    await sync.enqueue({ kind: 'source.upload', name: file.name, file })
    notice.value = '已在此浏览器保留上传内容，请在“保存与同步”中查看确认结果。'
    if (input.value) input.value.value = ''
  } catch (cause) { fail(cause) }
  finally { uploading.value = false }
}
</script>

<template>
  <section class="sources-intro">
    <p class="eyebrow">YOUR SOURCE LIBRARY</p>
    <h1>我的资料</h1>
    <p class="muted">保存面试资料，随时回到原文。</p>
  </section>
  <section class="upload-panel" aria-labelledby="upload-title">
    <h2 id="upload-title">添加一份 PDF</h2>
    <p>支持未加密、可读取的 PDF，单份不超过 50 MiB。</p>
    <label class="primary-button file-picker" :class="{ disabled: uploading || !sync.state }">
      {{ uploading ? '正在保留上传内容…' : '选择 PDF' }}
      <input ref="input" type="file" accept=".pdf,application/pdf" :disabled="uploading || !sync.state" aria-label="选择 PDF" @change="upload" />
    </label>
    <p class="form-note">相同文件保留为同一份资料和解析任务。上传后自动生成章节草稿；中断后保留原操作，重新连接时重试。</p>
    <button v-if="!sync.state" class="secondary-button" :disabled="loading" @click="load">连接资料服务</button>
  </section>
  <div v-if="error" role="alert" class="form-error">
    <p>{{ error.message }}</p><p v-if="error.requestId" class="request-id">请求编号 {{ error.requestId }}</p>
    <button class="secondary-button" :disabled="loading" @click="load">刷新资料列表</button>
  </div>
  <p v-if="notice" role="status" class="notice">{{ notice }}</p>
  <section class="source-list" aria-labelledby="sources-title" :aria-busy="loading">
    <div class="list-heading"><h2 id="sources-title">已保存的资料 <small>{{ sources.length }}</small></h2>
      <button class="secondary-button" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>
    <p v-if="loading && !sources.length" role="status">正在读取资料…</p>
    <p v-else-if="!sources.length && !error" class="empty-state">还没有资料，从第一份 PDF 开始。</p>
    <article v-for="source in sources" :key="source.id" class="source-row">
      <div><h3>{{ source.originalFilename }}</h3><p>{{ source.pageCount }} 页 · {{ (source.byteSize / 1024 / 1024).toFixed(2) }} MiB</p></div>
      <form v-if="editing?.id === source.id" class="rename-form" @submit.prevent="saveName">
        <label :for="`name-${source.id}`">资料名称</label>
        <input :id="`name-${source.id}`" v-model="editing.name" required maxlength="255" />
        <button class="secondary-button" type="submit">保存名称</button>
        <button class="secondary-button" type="button" @click="editing = null">取消</button>
      </form>
      <button v-else class="secondary-button" @click="edit(source)">修改名称</button>
      <a class="secondary-button" :href="`/api/sources/${source.id}/content`" target="_blank" rel="noopener">查看原文 <span class="sr-only">（新窗口）</span></a>
      <SourceImport :source-id="source.id" />
    </article>
  </section>
</template>
