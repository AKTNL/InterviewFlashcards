import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getMe, login, logout, type Profile } from '../api/auth'
import { ApiError } from '../api/client'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<Profile | null>(null)
  const checked = ref(false)
  const expired = ref(false)
  let pending: Promise<void> | undefined
  function clearSession() {
    expired.value = expired.value || user.value !== null
    user.value = null
    checked.value = true
  }
  async function refresh() {
    if (pending) return pending
    pending = (async () => {
      try { user.value = (await getMe()).data; checked.value = true }
      catch (error) {
        if (!(error instanceof ApiError) || error.code !== 'UNAUTHENTICATED') throw error
        clearSession()
      } finally { pending = undefined }
    })()
    return pending
  }
  async function signIn(username: string, password: string) {
    user.value = (await login(username, password)).data
    checked.value = true
    expired.value = false
  }
  async function signOut() {
    await logout()
    user.value = null
    checked.value = true
    expired.value = false
  }
  return { user, checked, expired, refresh, signIn, signOut, clearSession }
})
