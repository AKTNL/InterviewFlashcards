<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useSyncStore } from '../stores/sync'
import { readPreferences, savePreferences } from '../reading/preferences'
import { readingKey } from '../reading/shortcuts'

const props = withDefaults(defineProps<{ positionKey: string; enabled: boolean; canRate?: boolean }>(), { canRate: undefined })
const answer = defineModel<boolean>('answer', { required: true })
const hint = defineModel<boolean>('hint', { required: true })
const emit = defineEmits<{ rate: [rating: string] }>()
const auth = useAuthStore(), sync = useSyncStore()
const owner = computed(() => auth.user?.id ?? '')
const scope = computed(() => owner.value ? `${owner.value}:${sync.state?.dataEpoch ?? auth.user?.dataEpoch}` : '')
const preferences = ref(readPreferences(owner.value))
const root = ref<HTMLElement>(), restored = ref(false), storageFailed = ref(false)
const key = computed(() => scope.value && props.positionKey ? `reading.position.v1:${scope.value}:${props.positionKey}` : '')
let activeKey = '', restoring = false, mounted = false, generation = 0, saveTimer = 0
interface Position { answer: boolean; hint: boolean; y: number; details: number[]; anchor?: number; offset?: number }
const anchors = () => [...(root.value?.querySelectorAll('.source-block, .followup, .card-sources, [aria-label="个人笔记"]') ?? [])]
function persist() {
  if (!activeKey || restoring || !root.value || !props.enabled) return
  const details = [...root.value.querySelectorAll('details[data-reading-detail]')].flatMap((d, i) => (d as HTMLDetailsElement).open ? [i] : [])
  try {
    // Keep only a bounded set of positions in this tab. No card content is stored.
    const keys = Object.keys(sessionStorage).filter(k => k.startsWith('reading.position.v1:'))
    if (!keys.includes(activeKey) && keys.length >= 80) sessionStorage.removeItem(keys[0]!)
    const elements = anchors(), anchor = elements.findIndex(el => el.getBoundingClientRect().bottom > 0)
    sessionStorage.setItem(activeKey, JSON.stringify({ answer: answer.value, hint: hint.value, y: window.scrollY, details,
      anchor: anchor >= 0 ? anchor : undefined, offset: elements[anchor]?.getBoundingClientRect().top }))
  } catch { storageFailed.value = true }
}
async function restore() {
  if (!mounted || !props.enabled || activeKey === key.value) return
  activeKey = key.value; restored.value = false
  if (!activeKey) return
  let position: Position
  try { position = JSON.parse(sessionStorage.getItem(activeKey) ?? 'null') } catch { return }
  if (!position || typeof position.answer !== 'boolean' || typeof position.hint !== 'boolean' || !Number.isFinite(position.y) || position.y < 0) return
  const current = ++generation
  restoring = true; restored.value = true; answer.value = position.answer; hint.value = position.hint
  await nextTick()
  if (!mounted || current !== generation) return
  root.value?.querySelectorAll('details[data-reading-detail]').forEach((d, i) => { (d as HTMLDetailsElement).open = Array.isArray(position.details) && position.details.includes(i) })
  // Reserve image height in CSS so lazy source images do not move the restored text.
  requestAnimationFrame(() => {
    if (!mounted || current !== generation) return
    const anchor = Number.isInteger(position.anchor) ? anchors()[position.anchor!] : undefined
    const top = anchor && Number.isFinite(position.offset) ? window.scrollY + anchor.getBoundingClientRect().top - position.offset! : position.y
    window.scrollTo({ top: Math.max(0, top), behavior: 'instant' }); restoring = false
  })
}
watch(key, () => { persist(); generation++; restoring = false; activeKey = ''; void restore() }, { flush: 'pre' })
watch(() => props.enabled, () => { void restore() }, { flush: 'post' })
watch(owner, id => { preferences.value = readPreferences(id) })
watch(preferences, value => { storageFailed.value = !savePreferences(owner.value, value) }, { deep: true })
function scheduleSave() { window.clearTimeout(saveTimer); saveTimer = window.setTimeout(persist, 200) }
watch([answer, hint], scheduleSave)
function keyboard(event: KeyboardEvent) {
  if (!props.enabled || !preferences.value.shortcuts) return
  const key = readingKey(event)
  if (!key || ['1', '2', '3'].includes(key) && (!props.canRate || !answer.value)) return
  event.preventDefault()
  if (key === ' ') answer.value = !answer.value
  else if (key === 'h') hint.value = !hint.value
  else emit('rate', ({ '1': 'FORGOT', '2': 'VAGUE', '3': 'REMEMBERED' } as Record<string, string>)[key]!)
}
function reset() {
  generation++; restoring = false
  if (activeKey) { try { sessionStorage.removeItem(activeKey) } catch { /* optional storage */ } }
  answer.value = false; hint.value = false; restored.value = false
  root.value?.querySelectorAll('details[data-reading-detail]').forEach(d => { (d as HTMLDetailsElement).open = false })
  window.scrollTo({ top: root.value?.getBoundingClientRect().top! + window.scrollY || 0, behavior: 'instant' })
}
onMounted(() => {
  mounted = true; void restore()
  window.addEventListener('keydown', keyboard); window.addEventListener('pagehide', persist)
  window.addEventListener('scroll', scheduleSave, { passive: true })
})
onBeforeUnmount(() => {
  window.clearTimeout(saveTimer); persist(); mounted = false; generation++
  window.removeEventListener('keydown', keyboard); window.removeEventListener('pagehide', persist)
  window.removeEventListener('scroll', scheduleSave)
})
</script>
<template>
  <div ref="root" class="reading-pane" :data-theme="preferences.theme" :style="{ '--reading-font-size': `${preferences.fontSize}px` }">
    <details class="reading-settings" data-reading-no-shortcuts>
      <summary>阅读设置与快捷键</summary>
      <div class="reading-controls">
        <label>阅读字号<select v-model="preferences.fontSize" aria-label="阅读字号"><option v-for="size in [16, 18, 20, 22, 24]" :key="size" :value="size">{{ size }} px</option></select></label>
        <label>阅读主题<select v-model="preferences.theme" aria-label="阅读主题"><option value="light">明亮</option><option value="sepia">暖纸</option><option value="dark">深色</option></select></label>
        <label><input v-model="preferences.shortcuts" type="checkbox" />启用桌面快捷键</label>
        <button type="button" class="secondary-button" @click="reset">从头阅读</button>
      </div>
      <p>空格：展开 / 收起答案 · H：提示与要点<span v-if="canRate !== undefined"> · 1 / 2 / 3：不会 / 模糊 / 记住了（展开答案后）</span>。输入或聚焦控件、选中文字时不响应；评分仍需服务端确认。</p>
      <p>设置仅保存在当前浏览器；阅读位置仅保留在当前标签页，按账号与学习空间隔离。</p>
    </details>
    <p v-if="restored" role="status" class="reading-notice">已返回上次阅读位置，可在阅读设置中选择从头阅读。</p>
    <p v-if="storageFailed" role="status" class="reading-notice">浏览器未能保存阅读设置或位置，本次仍可正常阅读。</p>
    <slot />
  </div>
</template>
