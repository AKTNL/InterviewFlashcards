<script setup lang="ts">
import { computed, ref } from 'vue'
import { useSyncStore } from '../stores/sync'
import type { Mutation } from '../sync/types'
import { ratings } from '../api/cards'
import type { ReviewConflict } from '../sync/types'
const sync = useSyncStore()
const actionError = ref('')
const labels = { pending: '待同步', syncing: '同步中', synced: '已同步', failed: '失败', 'needs-attention': '待处理' }
const recent = computed(() => sync.items.filter(item => item.status !== 'synced').concat(sync.items.filter(item => item.status === 'synced').slice(-5).reverse()))
function remoteName(item: Mutation) {
  const details = item.error?.details as { current?: { originalFilename?: string } } | undefined
  return details?.current?.originalFilename
}
function download(item: Mutation) {
  const file = item.file ?? new Blob([JSON.stringify({ local: item.payload, server: item.error?.details }, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(file)
  const link = document.createElement('a'); link.href = url; link.download = item.file ? item.name : 'chapter-review-copy.json'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function action(item: Mutation, choice: 'resubmit' | 'discard' | 'security') {
  actionError.value = ''
  try {
    if (choice === 'resubmit') await sync.resubmit(item)
    else if (choice === 'security') await sync.retrySecurity(item)
    else await sync.discard(item)
  }
  catch (cause) { actionError.value = cause instanceof Error ? cause.message : '操作失败，请重试' }
}
async function resolve(conflict: ReviewConflict, choice: 'CURRENT' | 'CANDIDATE') {
  if (choice === 'CANDIDATE' && !window.confirm('确认用候选评分更正当前结果？这会重新计算该次评分的复习安排，不增加学习数量；已有后续学习时会拒绝更正。')) return
  actionError.value = ''
  try { await sync.resolve(conflict, choice) }
  catch (cause) { actionError.value = cause instanceof Error ? cause.message : '处理失败，请重试' }
}
</script>
<template>
  <section class="sync-panel" aria-labelledby="sync-title">
    <div class="list-heading"><h2 id="sync-title">保存与同步</h2>
      <span role="status">{{ labels[sync.status] }}</span>
      <button class="secondary-button" :disabled="sync.busy" @click="sync.refresh(true)">{{ sync.busy ? '同步中…' : '刷新与重试' }}</button>
    </div>
    <p v-if="sync.state" class="form-note">已连接服务端；待同步或待处理的修改仍需确认。</p>
    <p v-if="sync.error" role="alert" class="form-error">{{ sync.error }}</p>
    <p v-if="actionError" role="alert" class="form-error">{{ actionError }}</p>
    <article v-for="conflict in sync.conflicts" :key="conflict.id" class="notice">
      <h3>评分冲突待处理</h3>
      <RouterLink :to="`/cards/${conflict.cardId}`">查看题目与最新复习安排</RouterLink>
      <p>当前评分：{{ ratings[conflict.server.rating] }} · 学习日 {{ conflict.server.learningDate }} · 下次复习 {{ conflict.server.afterState.dueDate }}</p>
      <p>候选评分：{{ ratings[conflict.local.payload.rating] }}。候选尚未改变进度。</p>
      <div class="sync-actions">
        <button class="secondary-button" :disabled="sync.busy || sync.items.some(i => i.kind === 'conflict.resolve' && i.payload?.conflictId === conflict.id && i.status !== 'synced')" @click="resolve(conflict, 'CURRENT')">接受当前结果</button>
        <button class="secondary-button" :disabled="sync.busy || sync.items.some(i => i.kind === 'conflict.resolve' && i.payload?.conflictId === conflict.id && i.status !== 'synced')" @click="resolve(conflict, 'CANDIDATE')">使用候选更正此评分</button>
      </div>
    </article>
    <ul class="sync-items">
      <li v-for="item in recent" :key="item.operationId">
        <div class="sync-item-heading"><strong>{{ item.kind === 'conflict.resolve' ? '处理评分冲突' : item.kind === 'preferences.save' ? '学习设置' : item.kind === 'session.daily' ? '每日计划' : item.kind === 'review.write' ? '学习记录' : item.kind === 'session.start' ? '创建练习' : item.kind === 'card.update' ? '卡片整理' : item.kind === 'chapter.review' ? '章节检查' : item.kind === 'source.upload' ? '上传' : item.kind === 'import.retry' ? '重试解析' : '修改名称' }}：{{ item.name }}</strong>
          <span role="status" :class="['sync-label', item.status]">{{ labels[item.status] }}</span>
        </div>
        <p v-if="item.error">{{ item.error.message }}</p>
        <p v-if="item.error?.requestId" class="request-id">请求编号 {{ item.error.requestId }}</p>
        <p v-if="remoteName(item)">服务端名称：{{ remoteName(item) }}</p>
        <p v-if="item.result?.duplicate">相同 PDF 已存在，已关联原有资料。</p>
        <p v-if="item.result?.duplicateScore">此评分已由其他设备完成，未重复计入学习或推进复习。</p>
        <p v-if="item.result?.dueDate">下次复习：{{ item.result.dueDate }}{{ item.result.isReplay ? '（同日重练，阶段不升级）' : '' }}</p>
        <RouterLink v-if="item.result?.session" :to="`/practice/${item.result.session.id}`">继续此练习</RouterLink>
        <details v-if="item.kind === 'review.write' && item.error"><summary>核对评分与当前状态</summary><pre class="source-text">{{ JSON.stringify({ local: item.payload, server: item.error.details }, null, 2) }}</pre><button class="secondary-button" @click="download(item)">下载核对副本</button></details>
        <details v-if="(item.kind === 'chapter.review' || item.kind === 'card.update' || item.kind === 'preferences.save') && item.error"><summary>核对本地操作与服务端内容</summary><pre class="source-text">{{ JSON.stringify({ local: item.payload, server: item.error.details }, null, 2) }}</pre><button class="secondary-button" @click="download(item)">下载核对副本</button></details>
        <p v-if="item.status === 'pending'">内容已保留在此浏览器，收到服务端确认后才会显示已同步。</p>
        <div v-if="item.status === 'needs-attention' || item.status === 'failed'" class="sync-actions">
          <button v-if="item.file" class="secondary-button" @click="download(item)">保存本地 PDF 副本</button>
          <button v-if="item.kind === 'source.rename' && item.error?.code === 'VERSION_CONFLICT' && item.dataEpoch === sync.state?.dataEpoch" class="secondary-button" :disabled="sync.busy" @click="action(item, 'resubmit')">使用本地名称重新保存</button>
          <button v-if="(item.kind === 'preferences.save' || item.kind === 'card.update' || (item.kind === 'chapter.review' && item.action === 'EDIT')) && item.error?.code === 'VERSION_CONFLICT' && item.dataEpoch === sync.state?.dataEpoch" class="secondary-button" :disabled="sync.busy" @click="action(item, 'resubmit')">核对后使用本地编辑重新保存</button>
          <button v-if="item.error?.code === 'CSRF_INVALID' && item.dataEpoch === sync.state?.dataEpoch" class="secondary-button" :disabled="sync.busy" @click="action(item, 'security')">刷新安全凭证并重试</button>
          <button class="secondary-button" :disabled="sync.busy" @click="action(item, 'discard')">放弃此待同步操作</button>
        </div>
      </li>
    </ul>
  </section>
</template>
