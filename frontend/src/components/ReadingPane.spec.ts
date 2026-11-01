import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore } from '../stores/auth'
import { useSyncStore } from '../stores/sync'
import ReadingPane from './ReadingPane.vue'

const wrappers: { unmount(): void }[] = []
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear()
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { callback(0); return 1 })
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function view(position = 'card:one') {
  const pinia = createPinia(); setActivePinia(pinia)
  const auth = useAuthStore(), sync = useSyncStore()
  auth.user = { id: 'one', username: 'reader', learningTimezone: 'Asia/Shanghai', dataEpoch: 'epoch', revision: 1 }
  sync.state = { dataEpoch: 'epoch', revision: 1, sources: [] }
  const Host = defineComponent({ components: { ReadingPane }, setup: () => ({ answer: ref(false), hint: ref(false), enabled: ref(true), canRate: ref(true), position: ref(position), rate: vi.fn() }),
    template: `<ReadingPane v-model:answer="answer" v-model:hint="hint" :position-key="position" :enabled="enabled" :can-rate="canRate" @rate="rate"><p v-if="answer" class="source-block">完整答案</p><details data-reading-detail><summary>追问</summary>追问内容</details><input /><textarea /><div contenteditable="true" /><button>页面按钮</button></ReadingPane>` })
  const wrapper = mount(Host, { attachTo: document.body, global: { plugins: [pinia] } }); wrappers.push(wrapper); await flushPromises()
  return { wrapper, pane: wrapper.getComponent(ReadingPane), auth, sync }
}
function press(key: string, options: KeyboardEventInit = {}, target: EventTarget = window) {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...options }); target.dispatchEvent(event); return event
}
it('reveals by keyboard, requires an answer for scoring, and respects blocked scoring and repeated keys', async () => {
  const { wrapper } = await view()
  press('1'); expect(wrapper.vm.rate).not.toHaveBeenCalled()
  expect(press(' ').defaultPrevented).toBe(true); await flushPromises(); expect(wrapper.text()).toContain('完整答案')
  press('2'); expect(wrapper.vm.rate).toHaveBeenCalledExactlyOnceWith('VAGUE')
  press('2', { repeat: true }); expect(wrapper.vm.rate).toHaveBeenCalledTimes(1)
  wrapper.vm.canRate = false; await flushPromises(); press('3'); expect(wrapper.vm.rate).toHaveBeenCalledTimes(1)
  wrapper.vm.enabled = false; await flushPromises(); expect(press('h').defaultPrevented).toBe(false); expect(wrapper.vm.hint).toBe(false)
})
it('leaves native keyboard, editable content, selection, composition and modified shortcuts alone', async () => {
  const { wrapper } = await view()
  for (const selector of ['input', 'textarea', '[contenteditable]', 'button', 'summary', 'select']) {
    const event = press(' ', {}, wrapper.get(selector).element); expect(event.defaultPrevented).toBe(false)
  }
  for (const options of [{ ctrlKey: true }, { metaKey: true }, { altKey: true }, { shiftKey: true }, { isComposing: true }]) expect(press(' ', options).defaultPrevented).toBe(false)
  vi.spyOn(window, 'getSelection').mockReturnValue({ toString: () => '选中的答案' } as Selection)
  expect(press(' ').defaultPrevented).toBe(false); expect(wrapper.vm.answer).toBe(false)
})
it('persists per-user preferences, allows disabling single-key shortcuts, and applies size and theme', async () => {
  const { wrapper, pane, auth } = await view()
  await wrapper.get('select').setValue('24'); await wrapper.findAll('select')[1]!.setValue('dark'); await wrapper.get('input[type=checkbox]').setValue(false)
  expect(pane.attributes('data-theme')).toBe('dark'); expect(pane.attributes('style')).toContain('24px')
  expect(press(' ').defaultPrevented).toBe(false)
  expect(JSON.parse(localStorage.getItem('reading.preferences.v1:one')!)).toEqual({ fontSize: 24, theme: 'dark', shortcuts: false })
  auth.user = { ...auth.user!, id: 'two' }; await flushPromises()
  expect(pane.attributes('data-theme')).toBe('light'); expect(pane.attributes('style')).toContain('18px')
})
it('returns to the same answer, hint, follow-up and scroll position after leaving; reset closes them', async () => {
  const first = await view(); press(' '); press('h'); await flushPromises()
  ;(first.wrapper.get('details[data-reading-detail]').element as HTMLDetailsElement).open = true
  vi.spyOn(window, 'scrollY', 'get').mockReturnValue(640)
  first.wrapper.unmount()
  const second = await view(); await flushPromises()
  expect(second.wrapper.vm.answer).toBe(true); expect(second.wrapper.vm.hint).toBe(true)
  expect(second.wrapper.get('details[data-reading-detail]').attributes('open')).toBeDefined()
  expect(window.scrollTo).toHaveBeenCalledWith({ top: 640, behavior: 'instant' })
  expect(second.wrapper.text()).toContain('已返回上次阅读位置')
  await second.wrapper.get('button').trigger('click'); expect(second.wrapper.vm.answer).toBe(false); expect(second.wrapper.vm.hint).toBe(false)
})
it('does not carry a saved answer into another item, user or restored data epoch', async () => {
  const first = await view('practice:session:item:v1'); press(' '); await flushPromises(); first.wrapper.unmount()
  const second = await view('practice:session:next:v1'); expect(second.wrapper.vm.answer).toBe(false); second.wrapper.unmount()
  const third = await view('practice:session:item:v1'); expect(third.wrapper.vm.answer).toBe(true)
  third.sync.state = { dataEpoch: 'replaced', revision: 2, sources: [] }; third.wrapper.vm.answer = false; await flushPromises()
  expect(third.wrapper.vm.answer).toBe(false)
  third.auth.user = { ...third.auth.user!, id: 'another' }; await flushPromises(); expect(third.wrapper.vm.answer).toBe(false)
})
it('tolerates malformed and unavailable storage without blocking reading', async () => {
  localStorage.setItem('reading.preferences.v1:one', '{broken')
  sessionStorage.setItem('reading.position.v1:one:epoch:card:one', JSON.stringify({ answer: true, hint: false, y: -5 }))
  const { wrapper, pane } = await view(); expect(wrapper.vm.answer).toBe(false); expect(pane.attributes('data-theme')).toBe('light')
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
  await wrapper.get('select').setValue('22'); expect(wrapper.text()).toContain('本次仍可正常阅读')
  press(' '); await flushPromises(); expect(wrapper.vm.answer).toBe(true)
})
