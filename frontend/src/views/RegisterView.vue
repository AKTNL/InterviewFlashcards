<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { register, registrationEnabled } from '../api/auth'
import { ApiError } from '../api/client'
const router = useRouter()
const enabled = ref<boolean | null>(null), busy = ref(false), error = ref(''), uncertain = ref(false)
const invite = ref(''), username = ref(''), nickname = ref(''), password = ref(''), confirmation = ref('')
async function load() {
  error.value = ''
  try { enabled.value = (await registrationEnabled()).data } catch { error.value = '无法读取注册状态，请稍后重试。' }
}
onMounted(load)
async function submit() {
  if (busy.value || !enabled.value || uncertain.value) return
  error.value = ''
  if (password.value !== confirmation.value) { error.value = '两次密码不一致'; return }
  if (Array.from(password.value).length < 12 || new TextEncoder().encode(password.value).length > 72) { error.value = '密码至少 12 个字符、最多 72 个 UTF-8 字节'; return }
  busy.value = true
  try {
    await register(invite.value.trim(), username.value, password.value, nickname.value)
    invite.value = ''
    await router.replace('/login?registered=1')
  } catch (cause) {
    const failure = cause instanceof ApiError ? cause : null
    uncertain.value = !failure || failure.retryable || failure.code === 'ACCOUNT_CHANGED'
    error.value = uncertain.value ? '尚未收到注册确认。请先尝试使用刚填写的用户名和密码登录，避免重复注册；仍无法登录时联系维护者。' : failure!.message
  } finally { password.value = ''; confirmation.value = ''; busy.value = false }
}
</script>
<template>
  <section class="auth-panel" aria-labelledby="register-title">
    <p class="eyebrow">YOUR OWN LEARNING SPACE</p><h1 id="register-title">注册账号</h1>
    <p class="muted">使用维护者提供的邀请码，建立独立的个人学习空间。</p>
    <p v-if="enabled === null && !error" role="status">正在读取注册状态…</p>
    <p v-if="enabled === false" role="status">注册暂未开放，请联系维护者。</p>
    <form v-if="enabled && !uncertain" @submit.prevent="submit">
      <label for="invite">邀请码</label><input id="invite" v-model="invite" maxlength="100" autocomplete="off" required :disabled="busy" />
      <label for="new-username">用户名</label><input id="new-username" v-model="username" maxlength="32" autocomplete="username" required :disabled="busy" />
      <p class="form-note">3～32 位字母、数字或下划线，以字母开头；英文字母统一为小写。管理员等保留名不可用。</p>
      <label for="nickname">昵称（可选）</label><input id="nickname" v-model="nickname" maxlength="60" autocomplete="nickname" :disabled="busy" />
      <label for="new-password">密码</label><input id="new-password" v-model="password" type="password" autocomplete="new-password" required :disabled="busy" />
      <p class="form-note">至少 12 个字符、最多 72 个 UTF-8 字节，不含控制字符。</p>
      <label for="confirm-password">确认密码</label><input id="confirm-password" v-model="confirmation" type="password" autocomplete="new-password" required :disabled="busy" />
      <button class="primary-button" :disabled="busy">{{ busy ? '注册中…' : '创建账号' }}</button>
    </form>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <button v-if="enabled === null && error" class="secondary-button" @click="load">重新读取注册状态</button>
    <p><RouterLink to="/login">返回登录</RouterLink></p>
  </section>
</template>
