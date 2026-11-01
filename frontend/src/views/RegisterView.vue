<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { register, registrationEnabled, validateInvite } from '../api/auth'
import { ApiError } from '../api/client'
const router = useRouter()
const enabled = ref<boolean | null>(null), busy = ref(false), error = ref(''), uncertain = ref(false)
const invite = ref(''), username = ref(''), nickname = ref(''), password = ref(''), confirmation = ref('')
const step = ref<'invite' | 'account'>('invite')
let active = true
onUnmounted(() => { active = false; invite.value = ''; password.value = ''; confirmation.value = '' })
function back() { step.value = 'invite'; invite.value = ''; password.value = ''; confirmation.value = '' }
async function validate() {
  if(busy.value || !enabled.value) return
  busy.value = true; error.value = ''
  try { await validateInvite(invite.value.trim()); if(active) step.value = 'account' }
  catch(cause) { error.value = cause instanceof ApiError ? cause.message : '验证失败，请重试。' }
  finally { busy.value = false }
}
async function load() {
  error.value = ''
  try { enabled.value = (await registrationEnabled()).data } catch { error.value = '无法读取注册状态，请稍后重试。' }
}
onMounted(load)
async function submit() {
  if (busy.value || !enabled.value || uncertain.value || step.value !== 'account') return
  error.value = ''
  if (password.value !== confirmation.value) { error.value = '两次密码不一致'; return }
  if (Array.from(password.value).length < 12 || new TextEncoder().encode(password.value).length > 72) { error.value = '密码至少 12 个字符、最多 72 个 UTF-8 字节'; return }
  busy.value = true
  try {
    await register(invite.value.trim(), username.value, password.value, nickname.value)
    if(!active) return
    invite.value = ''
    await router.replace({ path: '/login', query: { registered: '1' }, state: { registeredUsername: username.value.trim().toLowerCase() } })
  } catch (cause) {
    const failure = cause instanceof ApiError ? cause : null
    uncertain.value = !failure || failure.retryable || failure.code === 'ACCOUNT_CHANGED'
    error.value = uncertain.value ? '尚未收到注册确认。请先尝试使用刚填写的用户名和密码登录，避免重复注册；仍无法登录时联系维护者。' : failure!.message
    if(failure && ['INVITE_INVALID','REGISTRATION_CLOSED','REGISTRATION_LIMITED'].includes(failure.code)) back()
  } finally { password.value = ''; confirmation.value = ''; busy.value = false }
}
</script>
<template>
  <section class="auth-panel" aria-labelledby="register-title">
    <p class="eyebrow">YOUR OWN LEARNING SPACE</p><h1 id="register-title">使用邀请码加入</h1>
    <p class="muted">先验证邀请，再设置账号。已有账号请直接登录。</p>
    <p v-if="enabled === null && !error" role="status">正在读取注册状态…</p>
    <p v-if="enabled === false" role="status">注册暂未开放，请联系维护者。</p>
    <form v-if="enabled && !uncertain && step === 'invite'" @submit.prevent="validate">
      <h2>1 · 验证邀请码</h2>
      <label for="invite">邀请码</label><input id="invite" v-model="invite" maxlength="100" autocomplete="off" required :disabled="busy" />
      <button class="primary-button" :disabled="busy">{{ busy ? '验证中…' : '验证邀请码' }}</button>
    </form>
    <form v-if="enabled && !uncertain && step === 'account'" @submit.prevent="submit">
      <h2>2 · 设置账号</h2><p class="form-note">验证不预留名额，创建账号时会再次核对邀请。</p>
      <label for="new-username">用户名</label><input id="new-username" v-model="username" maxlength="32" autocomplete="username" required :disabled="busy" />
      <p class="form-note">3～32 位字母、数字或下划线，以字母开头；英文字母统一为小写。管理员等保留名不可用。</p>
      <label for="nickname">昵称（可选）</label><input id="nickname" v-model="nickname" maxlength="60" autocomplete="nickname" :disabled="busy" />
      <label for="new-password">密码</label><input id="new-password" v-model="password" type="password" autocomplete="new-password" required :disabled="busy" />
      <p class="form-note">至少 12 个字符、最多 72 个 UTF-8 字节，不含控制字符。</p>
      <label for="confirm-password">确认密码</label><input id="confirm-password" v-model="confirmation" type="password" autocomplete="new-password" required :disabled="busy" />
      <button class="primary-button" :disabled="busy">{{ busy ? '注册中…' : '创建账号' }}</button>
      <button type="button" class="secondary-button" :disabled="busy" @click="back">重新填写邀请码</button>
    </form>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <button v-if="enabled === null && error" class="secondary-button" @click="load">重新读取注册状态</button>
    <p><RouterLink to="/login">返回登录</RouterLink></p>
  </section>
</template>
