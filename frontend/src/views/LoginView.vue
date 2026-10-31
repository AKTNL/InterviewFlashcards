<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/client'

const auth = useAuthStore()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref<ApiError | null>(null)
async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = null
  try {
    await auth.signIn(username.value, password.value)
    await router.replace('/sources')
  } catch (cause) { error.value = cause instanceof ApiError ? cause : new ApiError('UNKNOWN', '登录失败，请重试', 0) }
  finally { password.value = ''; busy.value = false }
}
</script>

<template>
  <section class="auth-panel" aria-labelledby="login-title">
    <p class="eyebrow">YOUR LEARNING SPACE</p>
    <h1 id="login-title">回到你的学习空间</h1>
    <p class="muted">手机与电脑，继续同一份积累。</p>
    <p v-if="auth.expired" role="status">登录已过期，请重新登录。</p>
    <form @submit.prevent="submit">
      <label for="username">账号</label>
      <input id="username" v-model="username" name="username" autocomplete="username" maxlength="100" required :disabled="busy" />
      <label for="password">密码</label>
      <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" required :disabled="busy" />
      <div v-if="error" role="alert" class="form-error">
        <p>{{ error.message }}</p><p v-if="error.requestId" class="request-id">请求编号 {{ error.requestId }}</p>
      </div>
      <button class="primary-button" :disabled="busy" type="submit">{{ busy ? '登录中…' : '进入学习空间' }}</button>
    </form>
    <p class="form-note">使用已配置的个人账号登录。</p>
  </section>
</template>
