<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { importsApi, statusNames, stageNames, type ImportJob } from '../api/imports'
import { useSyncStore } from '../stores/sync'
const props = defineProps<{ sourceId: string }>()
const sync = useSyncStore()
const job = ref<ImportJob>()
const error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let stopped = false
let busy = false
async function load() {
  if (busy || stopped) return
  busy = true; clearTimeout(timer)
  try { const jobs = await importsApi.jobs(props.sourceId); if (!stopped) { job.value = jobs[0]; error.value = '' } }
  catch (cause) { if (!stopped) error.value = cause instanceof Error ? cause.message : '任务状态读取失败' }
  finally {
    busy = false
    if (!stopped && (error.value || !job.value || ['QUEUED', 'PARSING'].includes(job.value.status))) timer = setTimeout(() => { void load() }, 2500)
  }
}
watch(() => sync.state?.revision, () => { void load() })
onMounted(() => { void load(); window.addEventListener('focus', load) })
onUnmounted(() => { stopped = true; clearTimeout(timer); window.removeEventListener('focus', load) })
</script>
<template>
  <div class="source-import">
    <p v-if="error" role="alert">{{ error }} <button class="secondary-button" @click="load">重试读取</button></p>
    <template v-if="job">
      <p role="status">{{ statusNames[job.status] }} · {{ stageNames[job.stage] ?? job.stage }} · {{ job.progress }}%</p>
      <p v-if="job.status === 'FAILED'" class="form-error">{{ job.errorMessage }}</p>
      <RouterLink class="secondary-button" :to="`/imports/${job.id}`">{{ job.status === 'READY' ? '查看章节与草稿' : '查看解析任务' }}</RouterLink>
    </template>
  </div>
</template>
