<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { ApiError } from './api/client'
import { useSyncStore } from './stores/sync'
import SyncPanel from './components/SyncPanel.vue'

const auth = useAuthStore()
const sync = useSyncStore()
watch(() => auth.user, user => {
  if (user) void sync.connect(user).catch(() => {})
  else sync.stop()
}, { immediate: true })
const router = useRouter()
const epochNotice = ref('')
watch(() => sync.state?.dataEpoch, (epoch, previous) => {
  if (epoch && previous && epoch !== previous) {
    epochNotice.value = '学习空间已恢复或替换，已重新加载。旧设备待提交内容已保留供核对，请查看“保存与同步”。'
    if (router.currentRoute.value.name !== 'backups') void router.replace('/')
  }
})
const logoutError = ref('')
const signingOut = ref(false)
function expired() {
  auth.clearSession()
  if (router.currentRoute.value.meta.requiresAuth) void router.replace('/login')
}
function activate() {
  if (document.visibilityState === 'visible' && auth.user) {
    void auth.refresh().catch(() => {})
    void sync.refresh()
  }
}
async function signOut() {
  if (sync.items.some(i => i.status !== 'synced') && !window.confirm('还有未同步或失败的操作。退出后保留在此浏览器，尚未保存到服务端；需登录同一账号继续处理。确认退出吗？')) return
  signingOut.value = true
  logoutError.value = ''
  try {
    await router.push('/login')
    if (router.currentRoute.value.path !== '/login') return
    await auth.signOut()
  }
  catch (error) { logoutError.value = error instanceof ApiError ? error.message : '退出失败，请重试' }
  finally { signingOut.value = false }
}
onMounted(() => {
  window.addEventListener('session-expired', expired)
  document.addEventListener('visibilitychange', activate)
  window.addEventListener('online', activate)
  window.addEventListener('focus', activate)
  void auth.refresh().catch(() => {})
})
onUnmounted(() => {
  window.removeEventListener('session-expired', expired)
  document.removeEventListener('visibilitychange', activate)
  window.removeEventListener('online', activate)
  window.removeEventListener('focus', activate)
  sync.stop()
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
        <RouterLink to="/">今日学习</RouterLink>
        <RouterLink to="/cards">题库</RouterLink>
        <RouterLink to="/settings">学习设置</RouterLink>
        <RouterLink to="/backups">备份与恢复</RouterLink>
        <RouterLink to="/sources">我的资料</RouterLink>
        <span class="account-name">{{ auth.user.username }}</span>
        <button class="secondary-button" :disabled="signingOut" @click="signOut">{{ signingOut ? '退出中…' : '退出' }}</button>
      </template>
      <RouterLink v-else to="/login">登录</RouterLink>
    </nav>
  </header>
  <main id="main" tabindex="-1">
    <p v-if="logoutError" role="alert" class="form-error">{{ logoutError }}</p>
    <p v-if="epochNotice" role="status" class="notice">{{ epochNotice }}</p>
    <RouterView :key="sync.state?.dataEpoch ?? auth.user?.dataEpoch ?? 'initial'" />
    <SyncPanel v-if="auth.user" />
  </main>
  <footer class="site-footer">每一次主动回忆，都让知识多留一会儿。</footer>
</template>
