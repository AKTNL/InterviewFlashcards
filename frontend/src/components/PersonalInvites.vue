<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { generateInvite, getInvites, revokeInvite, type InviteOverview } from '../api/auth'
import { ApiError } from '../api/client'
const overview = ref<InviteOverview | null>(null), token = ref(''), busy = ref(false), error = ref(''), notice = ref('')
const statuses = { AVAILABLE: '可使用', USED: '已使用', EXPIRED: '已过期', REVOKED: '已撤销' }
let active = true
async function load() {
  token.value = ''; overview.value = null
  try { const result = await getInvites(); if(active) overview.value = result.data }
  catch { if(active) error.value = '无法查询邀请状态，请先刷新状态；不要直接重新发码。' }
}
async function refresh() {
  if(busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await load() } finally { busy.value = false }
}
async function generate() {
  if(busy.value || !overview.value?.canGenerate) return
  busy.value = true; error.value = ''; notice.value = ''; token.value = ''
  try {
    const result = await generateInvite()
    if(!active) return
    await load()
    if(active && overview.value?.latest?.id === result.data.invite.id && overview.value.latest.status === 'AVAILABLE') token.value = result.data.token
  } catch(cause) {
    if(!active) return
    await load()
    if(active) error.value = cause instanceof ApiError && !cause.retryable ? cause.message : '未收到生成确认，已先查询状态。不会自动重新发码；已有有效码时，请撤销后再生成。'
  } finally { if(active) busy.value = false }
}
async function revoke() {
  const id = overview.value?.latest?.id
  if(busy.value || !id || !window.confirm('确认撤销此邀请码？旧码立即失效，已注册账号不受影响。需要再次分享时，请撤销后另行生成新码。')) return
  busy.value = true; error.value = ''; notice.value = ''; token.value = ''
  try { await revokeInvite(id); if(active) notice.value = '邀请码已撤销。' }
  catch(cause) { if(active) error.value = cause instanceof ApiError ? cause.message : '未收到撤销确认，请核对状态。' }
  finally { if(active) { await load(); busy.value = false } }
}
async function copy() {
  if(!token.value) return
  try { await navigator.clipboard.writeText(token.value); if(active) notice.value = '已复制，请私下交给朋友。' }
  catch { if(active) notice.value = '无法自动复制，请选中当前邀请码手动复制。' }
}
onMounted(refresh)
onUnmounted(() => { active = false; token.value = '' })
</script>
<template>
  <section aria-labelledby="invites-title">
    <h2 id="invites-title">我的邀请码</h2>
    <p class="muted">每次生成一枚，有效 7 天，可注册 1 人。注册后各自拥有独立空间；题库分享与加入小组需另行确认。</p>
    <template v-if="overview">
      <p>邀请权限：{{ overview.allowed ? '已授权' : '未授权' }} · 已成功 {{ overview.successfulCount }} / {{ overview.totalLimit }} 人 · 剩余名额 {{ overview.remainingQuota }}</p>
      <p v-if="overview.reason" role="status">{{ overview.reason }}</p>
      <div v-if="overview.latest" class="notice">
        <p>{{ overview.latest.label }} · {{ statuses[overview.latest.status] }}</p>
        <p>到期：{{ new Date(overview.latest.expiresAt).toLocaleString() }} · 剩余次数 {{ overview.latest.remainingUses }} · 已用 {{ overview.latest.usedCount }}</p>
        <template v-if="token">
          <label for="generated-invite">邀请码（仅本次显示，请及时复制）</label>
          <input id="generated-invite" :value="token" readonly autocomplete="off" spellcheck="false" />
          <button type="button" class="secondary-button" @click="copy">复制邀请码</button>
        </template>
        <p v-else-if="overview.latest.status === 'AVAILABLE'" class="form-note">仅保留脱敏标识，无法再次查看原码。需要再次分享时，明确撤销旧码后生成新码。</p>
        <p v-else class="form-note">明文仅生成时显示。可在剩余额度内生成新码，已注册账号不受影响。</p>
        <button v-if="overview.latest.status === 'AVAILABLE'" type="button" class="secondary-button" :disabled="busy" @click="revoke">撤销邀请码</button>
      </div>
      <button type="button" class="primary-button" :disabled="busy || !overview.canGenerate" @click="generate">生成邀请码</button>
    </template>
    <button type="button" class="secondary-button" :disabled="busy" @click="refresh">{{ busy ? '处理中…' : '刷新邀请状态（清除当前明文）' }}</button>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
  </section>
</template>
