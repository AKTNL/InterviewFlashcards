<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
GlobalWorkerOptions.workerSrc = workerUrl
const props = defineProps<{ sourceId: string; initialPage: number; bbox?: number[] }>()
const page = ref(props.initialPage), jump = ref(props.initialPage), count = ref(0), zoom = ref(1), busy = ref(false), error = ref('')
const canvas = ref<HTMLCanvasElement>(), host = ref<HTMLElement>(), width = ref(1), height = ref(1)
const renderedPage = ref(0)
let pdf: PDFDocumentProxy | undefined, render: RenderTask | undefined, generation = 0, mounted = false
let loadingTask: ReturnType<typeof getDocument> | undefined, observer: ResizeObserver | undefined
const highlight = computed(() => {
  const b = props.bbox
  if (page.value !== props.initialPage || !b || b.length !== 4 || !b.every(Number.isFinite)) return undefined
  return { left: `${b[0]! / width.value * 100}%`, top: `${b[1]! / height.value * 100}%`, width: `${(b[2]! - b[0]!) / width.value * 100}%`, height: `${(b[3]! - b[1]!) / height.value * 100}%` }
})
async function draw() {
  if (!pdf || !mounted) return
  const current = ++generation; render?.cancel(); busy.value = true; error.value = ''; renderedPage.value = 0
  const previous = render; render = undefined
  try {
    // Cancellation must settle before reusing a canvas.
    if (previous) await previous.promise.catch(() => {})
    const sheet = await pdf.getPage(page.value)
    if (current !== generation || !mounted) return
    const natural = sheet.getViewport({ scale: 1 }); width.value = natural.width; height.value = natural.height
    await nextTick()
    const target = canvas.value
    if (!target || current !== generation) return
    const scale = Math.min(2, (host.value?.clientWidth || 800) / natural.width) * zoom.value
    const viewport = sheet.getViewport({ scale }), ratio = Math.min(window.devicePixelRatio || 1, 2)
    target.width = Math.floor(viewport.width * ratio); target.height = Math.floor(viewport.height * ratio)
    target.style.width = `${viewport.width}px`; target.style.height = `${viewport.height}px`
    render = sheet.render({ canvas: target, viewport, transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0] })
    await render.promise
    if (current === generation) renderedPage.value = page.value
  } catch (cause) { if (current === generation && (cause as Error).name !== 'RenderingCancelledException') error.value = '页面读取失败，请重试或打开原 PDF。' }
  finally { if (current === generation) busy.value = false }
}
async function open() {
  const current = ++generation; render?.cancel(); pdf = undefined; busy.value = true; error.value = ''; count.value = 0
  const previous = loadingTask; loadingTask = undefined
  try {
    if (previous) await previous.destroy()
    if (current !== generation || !mounted) return
    loadingTask = getDocument({ url: `/api/sources/${props.sourceId}/content`, withCredentials: true,
      cMapUrl: '/pdfjs/cmaps/', cMapPacked: true, standardFontDataUrl: '/pdfjs/standard_fonts/', wasmUrl: '/pdfjs/wasm/' })
    const doc = await loadingTask.promise
    if (current !== generation || !mounted) { await doc.destroy(); return }
    pdf = doc; count.value = doc.numPages
    page.value = Math.max(1, Math.min(doc.numPages, Math.trunc(props.initialPage) || 1)); jump.value = page.value
    await draw()
  } catch { if (current === generation) { error.value = 'PDF 加载失败，请检查登录或网络后重试。'; busy.value = false } }
}
function go(value: number) { if (!count.value) return; page.value = Math.max(1, Math.min(count.value, Math.trunc(value) || 1)); jump.value = page.value; void draw() }
watch(() => props.sourceId, () => { if (mounted) void open() })
watch(() => props.initialPage, value => go(value))
watch(zoom, draw)
onMounted(() => { mounted = true; observer = new ResizeObserver(() => { if (!busy.value) void draw() }); if (host.value) observer.observe(host.value); void open() })
onUnmounted(() => { mounted = false; generation++; observer?.disconnect(); render?.cancel(); void loadingTask?.destroy() })
</script>
<template>
  <section ref="host" class="pdf-reader" aria-label="PDF 阅读器">
    <form class="pdf-toolbar" @submit.prevent="go(jump)"><button type="button" class="secondary-button" :disabled="!count || page <= 1" @click="go(page - 1)">上一页</button><label>物理页<input v-model.number="jump" type="number" min="1" :max="count || 1" /></label><span>/ {{ count || '…' }}</span><button class="secondary-button" :disabled="!count">跳转</button><button type="button" class="secondary-button" :disabled="!count || page >= count" @click="go(page + 1)">下一页</button><label>缩放<select v-model.number="zoom"><option :value="1">适合屏幕</option><option :value="1.5">150%</option><option :value="2">200%</option></select></label></form>
    <p v-if="busy" role="status">正在绘制物理页 {{ page }}…</p><p v-if="error" role="alert" class="form-error">{{ error }} <button class="secondary-button" @click="open">重试 PDF</button></p>
    <p v-if="highlight" class="form-note">金色框为此段来源范围；页码均为 PDF 物理页码。</p>
    <div class="pdf-scroll"><div class="pdf-sheet"><canvas ref="canvas" :aria-label="`PDF 物理页 ${page}`" :data-rendered-page="renderedPage" /><div v-if="highlight && !error && renderedPage === page" class="pdf-highlight" :style="highlight" /></div></div>
    <a :href="`/api/sources/${sourceId}/content#page=${page}`" target="_blank" rel="noopener">打开原 PDF（新窗口）</a>
  </section>
</template>
