import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { importsApi, type ImportJob } from '../api/imports'
import { useSyncStore } from '../stores/sync'
import ImportView from './ImportView.vue'
import DraftPreview from '../components/DraftPreview.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'job' } }), onBeforeRouteLeave: vi.fn() }))
vi.mock('../api/imports', async importOriginal => ({ ...await importOriginal<typeof import('../api/imports')>(),
  importsApi: { job: vi.fn(), chapters: vi.fn(), drafts: vi.fn(), draft: vi.fn(), review: vi.fn() } }))
const job: ImportJob = { id: 'job', sourceId: 'source', status: 'READY', stage: 'COMPLETE', progress: 100,
  errorCode: null, errorMessage: null, version: 7, parserVersion: 'v1',
  report: { pageCount: 222, tocCount: 158, candidateCount: 158, expectedCount: 158, recognitionDifference: 0, baselineDifference: 0,
    chapters: [{ name: 'Redis', expectedCount: 17, tocCount: 17, candidateCount: 17, recognitionDifference: 0, baselineDifference: 0 }] } }
const wrappers: { unmount(): void }[] = []
beforeEach(() => {
  vi.clearAllMocks(); setActivePinia(createPinia())
  vi.mocked(importsApi.job).mockResolvedValue(job)
  vi.mocked(importsApi.chapters).mockResolvedValue([{ id: 'chapter', name: 'Redis', originalName: 'Reids篇', module: 'Java 后端基础', checkStatus: 'PENDING', candidateCount: 17, flaggedCount: 17 }])
  vi.mocked(importsApi.drafts).mockResolvedValue({ offset: 0, limit: 20, total: 1, items: [{ id: 'draft', question: '完整问题', sourceEntry: 'q001', flags: [], excluded: false, version: 0 }] })
  vi.mocked(importsApi.review).mockResolvedValue({ id: 'chapter', name: 'Redis', version: 0, checkStatus: 'PENDING', abandoned: false, currentDraftId: null,
    items: [{ id: 'draft', question: '完整问题', sourceEntry: 'q001', flags: [], excluded: false, version: 0 }], cards: [], activeCount: 1, flaggedCount: 0, userCountDelta: 0, history: [] })
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()) })
function view() { const wrapper = mount(ImportView, { global: { plugins: [createPinia()], stubs: { RouterLink: true } } }); wrappers.push(wrapper); return wrapper }
it('shows chapter counts and original name, and loads selected candidates on demand', async () => {
  const wrapper = view(); await flushPromises()
  expect(wrapper.text()).toContain('222 页'); expect(wrapper.text()).toContain('目录 158 题')
  expect(wrapper.text()).toContain('Reids篇'); expect(wrapper.text()).toContain('Redis')
  expect(importsApi.drafts).not.toHaveBeenCalled()
  await wrapper.get('.chapter-buttons button').trigger('click'); await flushPromises()
  expect(importsApi.review).toHaveBeenCalledWith('chapter')
  expect(wrapper.text()).toContain('完整问题')
  expect(wrapper.find('.draft-list').text()).toContain('候选问题')
})
it('keeps parsing progress visible without offering drafts before READY', async () => {
  vi.mocked(importsApi.job).mockResolvedValue({ ...job, status: 'PARSING', stage: 'EXTRACTING', progress: 25, report: {} })
  const wrapper = view(); await flushPromises()
  expect(wrapper.text()).toContain('提取文字与来源位置'); expect(wrapper.text()).toContain('25%')
  expect(importsApi.chapters).not.toHaveBeenCalled(); expect(wrapper.find('.draft-list').exists()).toBe(false)
})
it('concentrates flagged chapters without including abandoned chapters or changing the current chapter', async () => {
  const base = { name: 'Redis', originalName: 'Redis', module: 'Java', checkStatus: 'PENDING', candidateCount: 2, flaggedCount: 0 }
  vi.mocked(importsApi.chapters).mockResolvedValue([{ ...base, id: 'normal' }, { ...base, id: 'flagged', flaggedCount: 1 }, { ...base, id: 'abandoned', abandoned: true, flaggedCount: 1 }])
  const wrapper = view(); await flushPromises()
  await wrapper.get('.anomaly-chapters input').setValue(true)
  expect(wrapper.findAll('.chapter-buttons button')).toHaveLength(1)
  expect(importsApi.review).not.toHaveBeenCalled()
  vi.mocked(importsApi.chapters).mockResolvedValue([{ ...base, id: 'normal' }])
  await wrapper.findAll('button').find(b => b.text() === '刷新任务')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('当前没有带待检查标记的章节')
})
it('shows actionable text failure and retains retry with the current task version', async () => {
  vi.mocked(importsApi.job).mockResolvedValue({ ...job, status: 'FAILED', stage: 'ANALYZING', errorCode: 'INSUFFICIENT_TEXT', errorMessage: '文本不足，请重新选择 PDF' })
  const wrapper = view(); await flushPromises()
  const sync = useSyncStore(); sync.state = { dataEpoch: 'epoch', revision: 1, sources: [] }
  const enqueue = vi.spyOn(sync, 'enqueue').mockResolvedValue(undefined)
  await flushPromises()
  expect(wrapper.text()).toContain('文本不足'); expect(wrapper.text()).toContain('重新选择 PDF')
  await wrapper.get('button.primary-button').trigger('click'); await flushPromises()
  expect(enqueue).toHaveBeenCalledWith({ kind: 'import.retry', name: 'PDF 解析', jobId: 'job', expectedVersion: 7 })
  expect(wrapper.text()).toContain('本机保留')
})
it('preserves complete extracted text, flags, followups and authenticated page fallbacks', async () => {
  vi.mocked(importsApi.draft).mockResolvedValue({ id: 'draft', sourceId: 'source', question: '问题', sourceEntry: 'q001', excluded: false, version: 0,
    flags: [{ kind: 'IMAGE_REQUIRES_SOURCE', page: 10, reason: '图片需核对来源' }], sources: [{ page: 10, bbox: [0, 30, 600, 800] }],
    contentBlocks: [{ type: 'SOURCE_TEXT', page: 10, textSpans: [{ text: '代码第一行', bbox: [] }, { text: '答案最后一行', bbox: [] }], images: [{ bbox: [] }] }],
    followups: [{ page: 10, title: '追加：追问', text: '追加：追问\n完整追问答案' }] })
  const wrapper = mount(DraftPreview, { props: { id: 'draft' } }); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.get('pre').text()).toBe('代码第一行\n答案最后一行')
  expect(wrapper.text()).toContain('图片需核对来源'); expect(wrapper.text()).toContain('完整追问答案')
  expect(wrapper.get('a').attributes('href')).toBe('/api/sources/source/content#page=10')
  expect(wrapper.find('img').exists()).toBe(false)
  const details = wrapper.get('.source-block details'); (details.element as HTMLDetailsElement).open = true
  await details.trigger('toggle')
  expect(wrapper.get('img').attributes('src')).toBe('/api/sources/source/pages/10/image')
})
