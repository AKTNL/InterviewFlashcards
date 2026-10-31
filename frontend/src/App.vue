<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { ApiError } from './api/client'

const auth = useAuthStore()
const router = useRouter()
const logoutError = ref('')
const signingOut = ref(false)
function expired() {
  auth.clearSession()
  if (router.currentRoute.value.meta.requiresAuth) void router.replace('/login')
}
function activate() {
  if (document.visibilityState === 'visible' && auth.user) void auth.refresh().catch(() => {})
}
async function signOut() {
  signingOut.value = true
  logoutError.value = ''
  try { await auth.signOut(); await router.replace('/login') }
  catch (error) { logoutError.value = error instanceof ApiError ? error.message : '退出失败，请重试' }
  finally { signingOut.value = false }
}
onMounted(() => {
  window.addEventListener('session-expired', expired)
  document.addEventListener('visibilitychange', activate)
  void auth.refresh().catch(() => {})
})
onUnmounted(() => {
  window.removeEventListener('session-expired', expired)
  document.removeEventListener('visibilitychange', activate)
})
</script>

<template>
  <a class="skip-link" href="#main">跳转到内容</a>
  <header class="site-header">
    <RouterLink class="brand" to="/" aria-label="拾忆，回到首页">
      <span class="brand-icon" aria-hidden="true">拾</span>
      <span>拾忆 <small>面试卡片</small></span>
    </RouterLink>
    <nav class="account-nav" aria-label="个人空间">
      <template v-if="auth.user">
        <RouterLink to="/sources">我的资料</RouterLink>
        <span class="account-name">{{ auth.user.username }}</span>
        <button class="secondary-button" :disabled="signingOut" @click="signOut">{{ signingOut ? '退出中…' : '退出' }}</button>
      </template>
      <RouterLink v-else to="/login">登录</RouterLink>
    </nav>
  </header>
  <main id="main" tabindex="-1">
    <p v-if="logoutError" role="alert" class="form-error">{{ logoutError }}</p>
    <RouterView />
  </main>
  <footer class="site-footer">每一次主动回忆，都让知识多留一会儿。</footer>
</template>
