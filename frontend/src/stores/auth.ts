import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getMe, login, logout, type Profile } from '../api/auth'
import { ApiError, announceIdentityChange, setRequestAccount } from '../api/client'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<Profile | null>(null)
  const checked = ref(false)
  const expired = ref(false)
  const endingSession = ref(false)
  let pending: Promise<void> | undefined
  function clearSession() {
    expired.value = expired.value || user.value !== null
    user.value = null
    setRequestAccount(null)
    checked.value = true
  }
  async function refresh() {
    if (pending) return pending
    pending = (async () => {
      try {
        const profile = (await getMe()).data
        if (user.value && user.value.id !== profile.id) clearSession()
        setRequestAccount(profile.id); user.value = profile; checked.value = true
      }
      catch (error) {
        if (!(error instanceof ApiError) || error.code !== 'UNAUTHENTICATED') throw error
        clearSession()
      } finally { pending = undefined }
    })()
    return pending
  }
  async function signIn(username: string, password: string) {
    announceIdentityChange(); user.value = null; setRequestAccount(null)
    user.value = (await login(username, password)).data
    setRequestAccount(user.value.id)
    checked.value = true
    expired.value = false
  }
  async function signOut() {
    endingSession.value = true
    try {
      announceIdentityChange()
      await logout()
      user.value = null
      setRequestAccount(null)
      checked.value = true
      expired.value = false
    } finally { endingSession.value = false }
  }
  return { user, checked, expired, endingSession, refresh, signIn, signOut, clearSession }
})
