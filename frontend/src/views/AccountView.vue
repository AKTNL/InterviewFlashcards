<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '../api/auth'
import { ApiError, announceIdentityChange } from '../api/client'
import { useAuthStore } from '../stores/auth'
import PersonalInvites from '../components/PersonalInvites.vue'
const auth = useAuthStore(), router = useRouter()
const current = ref(''), password = ref(''), confirmation = ref(''), busy = ref(false), error = ref('')
async function submit() {
  if (busy.value) return
  error.value = ''
  if(password.value !== confirmation.value) { error.value = '两次密码不一致'; return }
  busy.value = true
  try {
    await changePassword(current.value,password.value)
    announceIdentityChange(); auth.clearSession()
    await router.replace('/login?passwordChanged=1')
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '修改密码失败，请重试'
    if(cause instanceof ApiError && cause.retryable) error.value = '尚未收到改密确认，请退出后使用新密码尝试登录；未成功时再使用原密码。'
  } finally { current.value = ''; password.value = ''; confirmation.value = ''; busy.value = false }
}
</script>
<template>
  <section class="auth-panel" aria-labelledby="account-title">
    <h1 id="account-title">个人中心</h1><p>登录名：{{ auth.user?.username }}</p>
    <p v-if="auth.user?.nickname">昵称：{{ auth.user.nickname }}</p>
    <PersonalInvites />
    <h2>修改密码</h2><p class="muted">修改后所有设备需要重新登录。忘记密码请联系部署维护者。</p>
    <form @submit.prevent="submit">
      <label for="current-password">当前密码</label><input id="current-password" v-model="current" type="password" autocomplete="current-password" required :disabled="busy" />
      <label for="replacement-password">新密码</label><input id="replacement-password" v-model="password" type="password" autocomplete="new-password" required :disabled="busy" />
      <p class="form-note">至少 12 个字符、最多 72 个 UTF-8 字节，不含控制字符。</p>
      <label for="replacement-confirmation">确认新密码</label><input id="replacement-confirmation" v-model="confirmation" type="password" autocomplete="new-password" required :disabled="busy" />
      <p v-if="error" role="alert" class="form-error">{{ error }}</p>
      <button class="primary-button" :disabled="busy">{{ busy ? '修改中…' : '修改密码并重新登录' }}</button>
    </form>
  </section>
</template>
