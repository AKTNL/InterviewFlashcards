<script setup lang="ts">
import { onUnmounted, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { heartbeat } from '../api/dashboard'
import { UsageTracker } from '../usage/tracker'
const auth = useAuthStore()
let cleanup: (() => void) | undefined
watch(() => `${auth.user?.id ?? ''}:${!!auth.user?.mustChangePassword}:${auth.endingSession}`, () => {
  cleanup?.(); cleanup = undefined
  if (!auth.user || auth.user.mustChangePassword || auth.endingSession) return
  const tracker = new UsageTracker({ now: () => Date.now(), eligible: () => document.visibilityState === 'visible' && navigator.onLine, id: () => crypto.randomUUID(), send: heartbeat })
  const activity = (event: Event) => { if (event.isTrusted) tracker.interact() }
  const suspend = () => tracker.suspend()
  const visibility = () => { if (document.visibilityState !== 'visible') suspend() }
  const events = ['pointerdown', 'keydown', 'scroll', 'wheel', 'touchstart']
  events.forEach(name => window.addEventListener(name, activity, { passive: true, capture: true }))
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('offline', suspend); window.addEventListener('pagehide', suspend)
  window.addEventListener('blur', suspend)
  window.addEventListener('session-expired', suspend); window.addEventListener('identity-changed', suspend)
  // Check often enough that a timer just before the first beat's 30-second boundary
  // cannot defer the next attempt until a full minute (and break continuity).
  const timer = setInterval(() => { void tracker.tick() }, 5_000)
  cleanup = () => {
    tracker.dispose(); clearInterval(timer)
    events.forEach(name => window.removeEventListener(name, activity, true))
    document.removeEventListener('visibilitychange', visibility)
    window.removeEventListener('offline', suspend); window.removeEventListener('pagehide', suspend)
    window.removeEventListener('blur', suspend)
    window.removeEventListener('session-expired', suspend); window.removeEventListener('identity-changed', suspend)
  }
}, { immediate: true, flush: 'sync' })
onUnmounted(() => cleanup?.())
</script>
<template><span hidden aria-hidden="true"></span></template>
