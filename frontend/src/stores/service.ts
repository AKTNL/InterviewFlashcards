import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiError, type ResponseMeta } from '../api/client'
import { getHealth, type Health } from '../api/health'

export const useServiceStore = defineStore('service', () => {
  const health = ref<Health | null>(null)
  const meta = ref<ResponseMeta | null>(null)
  const loading = ref(false)
  const error = ref<ApiError | null>(null)

  async function check() {
    if (loading.value) return
    loading.value = true
    error.value = null
    health.value = null
    meta.value = null
    try {
      const result = await getHealth()
      health.value = result.data
      meta.value = result.meta
    } catch (cause) {
      error.value = cause instanceof ApiError ? cause : new ApiError('UNKNOWN_ERROR', '检查失败，请重试', 0)
    } finally {
      loading.value = false
    }
  }

  return { health, meta, loading, error, check }
})
