<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { backupsApi, type BackupJob } from '../api/backups'
import { useSyncStore } from '../stores/sync'
const sync = useSyncStore()
const exports = ref<BackupJob[]>([]), restores = ref<BackupJob[]>([])
const selected = ref(''), file = ref<File>(), busy = ref(false), loading = ref(false), error = ref(''), message = ref(''), confirmed = ref(false)
const job = computed(() => restores.value.find(r => r.id === selected.value))
const pending = computed(() => sync.items.filter(i => i.status !== 'synced').length)
const active = (j: BackupJob) => ['QUEUED', 'RUNNING', 'VALIDATING', 'RESTORING'].includes(j.status)
const labels: Record<string, string> = { QUEUED: '等待处理', RUNNING: '正在生成备份', VALIDATING: '正在校验文件与数据关联', AWAITING_CONFIRMATION: '校验通过，等待替换确认', RESTORING: '正在准备文件并恢复', READY: '已完成', FAILED: '失败，当前学习数据未替换' }
const errors: Record<string, string> = {
  BACKUP_VERSION_UNSUPPORTED: '此备份的格式版本不受支持，请使用兼容版本导出。当前数据未替换。',
  BACKUP_INVALID: '备份损坏、缺少文件、格式版本不受支持或数据关联不合法。当前数据未替换。',
  BACKUP_FAILED: '备份生成失败，请检查文件存储或重新导出。', RESTORE_FAILED: '恢复未完成，原学习空间仍可使用，请重新上传预检。',
  RESTORE_STALE: '预览后当前数据已有变化，请刷新影响预览并重新确认。', RESTORE_INTERRUPTED: '服务重启中断了恢复，请刷新预览并重新确认。',
  BACKUP_INTERRUPTED: '服务重启中断了导出，请重新导出。', BACKUP_EXPIRED: '临时备份已超过 7 天，请重新导出或上传。',
}
const rows = [ ['source_document', '来源 PDF'], ['asset', '图片资源'], ['deck', '题库'], ['chapter', '章节'], ['card', '正式卡片'], ['draft_card', '草稿'], ['draft_edit_operation', '调整记录'], ['personal_annotation', '笔记 / 提示 / 收藏'], ['source_span', '来源定位'], ['follow_up', '追问'], ['tag', '标签'], ['card_tag', '卡片标签关联'], ['chapter_check_progress', '检查进度'], ['import_job', '解析任务'], ['module', '模块'], ['review_event', '学习事件（含撤销）'], ['review_state', '复习状态'], ['learning_preference', '学习偏好'], ['learning_preference_chapter', '学习范围'], ['learning_session', '学习会话'], ['session_item', '会话条目'], ['review_replay_allowance', '重练记录'], ['sync_conflict', '冲突记录'] ] as const
let timer: ReturnType<typeof setTimeout> | undefined, disposed = false, exportOperation = '', uploadOperation = ''
function schedule() { clearTimeout(timer); if (!disposed && [...exports.value, ...restores.value].some(active)) timer = setTimeout(() => void load(), 1500) }
async function load() {
  if (loading.value || busy.value) return
  loading.value = true
  try {
    const [e, r] = await Promise.all([backupsApi.exports(), backupsApi.restores()])
    if (disposed) return
    const old = job.value?.status
    exports.value = e; restores.value = r
    if (!selected.value) selected.value = r[0]?.id ?? ''
    if (old === 'RESTORING' && job.value?.status === 'READY') { message.value = '恢复完成，复习日期与备份时一致。旧设备待提交内容需要重新核对。'; await sync.refresh(true) }
    error.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取任务失败，请重试' }
  finally { loading.value = false; schedule() }
}
async function action(work: () => Promise<void>) {
  if (busy.value || loading.value) return
  busy.value = true; error.value = ''
  try { await work() } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败，请重试' }
  finally { busy.value = false; schedule() }
}
function exportNow() { return action(async () => {
  exportOperation ||= crypto.randomUUID()
  const result = await backupsApi.export(exportOperation); exportOperation = ''
  exports.value = [result, ...exports.value.filter(j => j.id !== result.id)]
  message.value = '导出已排队。可继续学习，完成后在本页下载。备份只包含服务端已保存的数据。'
}) }
function choose(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0]; uploadOperation = ''; confirmed.value = false
}
function upload() { return action(async () => {
  if (!file.value) return
  if (file.value.size > 50 * 1024 * 1024) throw new Error('ZIP 不能超过 50 MiB')
  uploadOperation ||= crypto.randomUUID()
  const result = await backupsApi.upload(file.value, uploadOperation); uploadOperation = ''
  restores.value = [result, ...restores.value.filter(j => j.id !== result.id)]; selected.value = result.id; confirmed.value = false
  message.value = '备份已上传，正在后台预检。校验通过后仍需明确确认替换。'
}) }
function preview() { return action(async () => {
  if (!job.value) return
  const result = await backupsApi.preview(job.value.id)
  restores.value = restores.value.map(j => j.id === result.id ? result : j); confirmed.value = false
}) }
function restore() { return action(async () => {
  if (!job.value || !confirmed.value) return
  const result = await backupsApi.confirm(job.value)
  restores.value = restores.value.map(j => j.id === result.id ? result : j); confirmed.value = false
  message.value = '已提交恢复，正在准备资源并替换学习空间。'
}) }
onMounted(load)
onUnmounted(() => { disposed = true; clearTimeout(timer) })
</script>
<template>
  <RouterLink to="/">← 返回今日学习</RouterLink>
  <section class="sources-intro"><p class="eyebrow">BACKUP · 备份与恢复</p><h1>保存完整的学习空间</h1><p>备份包含来源 PDF、图片、正式卡片、未确认草稿、笔记、历史、复习日期、偏好与可恢复会话。账号密码与登录凭证不包含在内。</p></section>
  <p v-if="error" role="alert" class="form-error">{{ error }} <button class="secondary-button" :disabled="loading" @click="load">刷新任务</button></p>
  <p v-if="message" role="status" class="notice">{{ message }}</p>
  <p v-if="pending" class="notice">还有 {{ pending }} 项本地内容未获服务端确认，备份不会包含这些内容。请先检查下方“保存与同步”。</p>
  <section class="chapter-review backup-section"><h2>导出与下载</h2><p>导出固定一个时点的已保存数据，生成期间可继续学习。临时下载和上传文件保留 7 天，请将 ZIP 保存到自己的设备。</p>
    <button class="primary-button" :disabled="busy || loading" @click="exportNow">导出当前完整数据</button>
    <p v-if="loading && !exports.length" role="status">正在读取任务…</p><p v-else-if="!exports.length">还没有导出记录。</p>
    <ul class="backup-jobs"><li v-for="e in exports" :key="e.id"><time>{{ new Date(e.created_at).toLocaleString() }}</time> · {{ labels[e.status] }}
      <progress v-if="active(e)" :value="e.progress" max="100" :aria-label="labels[e.status]" />
      <a v-if="e.status === 'READY'" :href="`/api/backups/${e.id}/download`" download>下载完整 ZIP</a><p v-if="e.error_code" class="form-error">{{ errors[e.error_code] ?? '任务未完成，请重新操作。' }}</p>
    </li></ul>
  </section>
  <section class="chapter-review backup-section"><h2>上传与安全恢复</h2><p>恢复将替换当前登录用户的整个学习空间。建议先导出当前数据，确认下载已保存后再操作。历史学习日和 dueDate 按备份原样保留，逾期题仍然逾期。备份中未完成的解析需显式重试。</p>
    <form @submit.prevent="upload"><label>选择完整备份 ZIP（最多 50 MiB）<input type="file" accept=".zip,application/zip" :disabled="busy || loading" @change="choose" /></label><button class="secondary-button" type="submit" :disabled="busy || loading || !file">上传并预检</button></form>
    <label v-if="restores.length">恢复任务<select v-model="selected" @change="confirmed = false"><option v-for="r in restores" :key="r.id" :value="r.id">{{ new Date(r.created_at).toLocaleString() }} · {{ labels[r.status] }}</option></select></label>
    <div v-if="job" aria-live="polite"><p>{{ labels[job.status] }}</p><progress v-if="active(job)" :value="job.progress" max="100" :aria-label="labels[job.status]" />
      <p v-if="job.error_code" class="form-error">{{ errors[job.error_code] ?? '任务未完成，请重新上传预检。' }}</p>
      <template v-if="job.validation_result">
        <p>备份时间：{{ new Date(job.validation_result.manifest.createdAt).toLocaleString() }} · 格式版本 {{ job.validation_result.manifest.formatVersion }}</p>
        <p>{{ job.validation_result.scope }}</p>
        <div class="backup-counts"><table><caption>替换影响预览</caption><thead><tr><th>数据</th><th>当前空间</th><th>备份</th></tr></thead><tbody><tr v-for="[key, label] in rows" :key="key"><th>{{ label }}</th><td>{{ job.validation_result.currentCounts?.[key] ?? '—' }}</td><td>{{ job.validation_result.manifest.counts[key] ?? 0 }}</td></tr></tbody></table></div>
      </template>
      <div v-if="job.status === 'AWAITING_CONFIRMATION'" class="restore-confirm">
        <button class="secondary-button" :disabled="busy || loading" @click="preview">刷新影响预览</button>
        <label><input v-model="confirmed" type="checkbox" :disabled="busy || loading" />我已核对替换范围，并确认用此备份替换当前全部学习数据。</label>
        <p>恢复后其他设备会重新加载，旧会话不能继续写入，恢复前离线编辑将保留供核对，不会自动覆盖恢复结果。</p>
        <button class="primary-button" :disabled="busy || loading || !confirmed" @click="restore">确认替换并恢复</button>
      </div>
      <p v-if="job.status === 'READY'" class="notice">恢复成功。复习日期、笔记、草稿和来源资源已恢复。<RouterLink to="/">查看今日学习</RouterLink></p>
    </div>
  </section>
</template>
