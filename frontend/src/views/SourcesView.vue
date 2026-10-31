<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, request } from '../api/client'

interface Source {
  id: string; originalFilename: string; pageCount: number; byteSize: number; createdAt: string
}
const sources = ref<Source[]>([])
const loading = ref(false)
const uploading = ref(false)
const error = ref<ApiError | null>(null)
const notice = ref('')
const input = ref<HTMLInputElement>()
function fail(cause: unknown) {
  error.value = cause instanceof ApiError ? cause : new ApiError('UNKNOWN', '操作失败，请重试', 0)
}
async function load() {
  loading.value = true
  error.value = null
  try { sources.value = (await request<Source[]>('/api/sources')).data }
  catch (cause) { fail(cause) }
  finally { loading.value = false }
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
  const form = new FormData()
  form.append('file', file)
  try {
    const result = (await request<{ source: Source; duplicate: boolean }>('/api/sources', {
      method: 'POST', body: form,
    })).data
    notice.value = result.duplicate ? '这份 PDF 已保存，已为你找到原有资料。' : 'PDF 已保存，可以查看原文。自动解析与逐章检查将在后续开放。'
    await load()
  } catch (cause) { fail(cause) }
  finally { uploading.value = false; if (input.value) input.value.value = '' }
}
onMounted(load)
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
    <label class="primary-button file-picker" :class="{ disabled: uploading }">
      {{ uploading ? '正在上传并检查…' : '选择 PDF' }}
      <input ref="input" type="file" accept=".pdf,application/pdf" :disabled="uploading" aria-label="选择 PDF" @change="upload" />
    </label>
    <p class="form-note">相同文件会保留为同一份资料。上传较慢或超时后，可重新选择文件核对。</p>
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
      <a class="secondary-button" :href="`/api/sources/${source.id}/content`" target="_blank" rel="noopener">查看原文 <span class="sr-only">（新窗口）</span></a>
    </article>
  </section>
</template>
