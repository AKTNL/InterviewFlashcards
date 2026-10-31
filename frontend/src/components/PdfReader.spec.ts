import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import PdfReader from './PdfReader.vue'
const mocks = vi.hoisted(() => ({ getDocument: vi.fn(), getPage: vi.fn(), destroy: vi.fn(), render: vi.fn() }))
vi.mock('pdfjs-dist', () => ({ GlobalWorkerOptions: {}, getDocument: mocks.getDocument }))
const wrappers: { unmount(): void }[] = []
beforeEach(() => {
  vi.clearAllMocks();vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  mocks.render.mockImplementation(() => ({ promise: Promise.resolve(), cancel: vi.fn() }))
  mocks.getPage.mockResolvedValue({ getViewport: ({ scale }: { scale: number }) => ({ width: 600 * scale, height: 800 * scale }), render: mocks.render })
  mocks.getDocument.mockReturnValue({ promise: Promise.resolve({ numPages: 5, getPage: mocks.getPage }), destroy: mocks.destroy })
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount());vi.unstubAllGlobals() })
async function view() { const wrapper = mount(PdfReader, { props: { sourceId: 'source', initialPage: 3, bbox: [0, 20, 300, 400] } });wrappers.push(wrapper);await flushPromises();return wrapper }
it('loads authenticated local PDF assets, renders physical pages and clamps navigation to the document', async () => {
  const wrapper = await view()
  expect(mocks.getDocument).toHaveBeenCalledWith(expect.objectContaining({ url: '/api/sources/source/content', withCredentials: true, cMapUrl: '/pdfjs/cmaps/' }))
  expect(mocks.getPage).toHaveBeenCalledWith(3);expect(wrapper.get('canvas').attributes('data-rendered-page')).toBe('3')
  expect(wrapper.get('.pdf-highlight').attributes('style')).toContain('width: 50%')
  await wrapper.get('input').setValue(99);await wrapper.get('form').trigger('submit');await flushPromises()
  expect(mocks.getPage).toHaveBeenLastCalledWith(5);expect(wrapper.get('canvas').attributes('data-rendered-page')).toBe('5');expect(wrapper.find('.pdf-highlight').exists()).toBe(false)
  await wrapper.setProps({ initialPage: 1 });await flushPromises();expect(mocks.getPage).toHaveBeenLastCalledWith(1)
})
it('shows a retry and original-file fallback after load failure instead of claiming a rendered page', async () => {
  mocks.getDocument.mockReturnValueOnce({ promise: Promise.reject(new Error('offline')), destroy: mocks.destroy })
  const wrapper = await view();expect(wrapper.get('[role=alert]').text()).toContain('PDF 加载失败');expect(wrapper.get('canvas').attributes('data-rendered-page')).toBe('0')
  expect(wrapper.get('a').attributes('href')).toBe('/api/sources/source/content#page=3')
  await wrapper.findAll('button').find(b => b.text() === '重试 PDF')!.trigger('click');await flushPromises();expect(wrapper.get('canvas').attributes('data-rendered-page')).toBe('3')
})
it('does not mark an unfinished or failed render as complete', async () => {
  let rejectRender: (e: Error) => void = () => {}
  mocks.render.mockReturnValueOnce({ promise: new Promise((_, reject) => { rejectRender = reject }), cancel: vi.fn() })
  const wrapper = await view();expect(wrapper.get('canvas').attributes('data-rendered-page')).toBe('0');expect(wrapper.find('.pdf-highlight').exists()).toBe(false)
  rejectRender(new Error('render failed'));await flushPromises();expect(wrapper.get('[role=alert]').text()).toContain('页面读取失败');expect(wrapper.get('canvas').attributes('data-rendered-page')).toBe('0')
})
it('redraws on width changes in the next frame and ignores its own canvas height notifications', async () => {
  let resize = () => {}, nextFrame = () => {}
  vi.stubGlobal('ResizeObserver', class { constructor(callback: () => void) { resize = callback } observe() {} disconnect() {} })
  const frame = vi.fn((callback: () => void) => { nextFrame = callback;return 1 })
  vi.stubGlobal('requestAnimationFrame', frame);vi.stubGlobal('cancelAnimationFrame', vi.fn())
  const wrapper = await view();let readerWidth = 320
  Object.defineProperty(wrapper.get('.pdf-reader').element, 'clientWidth', { get: () => readerWidth })
  mocks.render.mockClear();resize();expect(mocks.render).not.toHaveBeenCalled();nextFrame();await flushPromises();expect(mocks.render).toHaveBeenCalledTimes(1)
  resize();resize();expect(frame).toHaveBeenCalledTimes(1);expect(mocks.render).toHaveBeenCalledTimes(1)
  readerWidth = 400;resize();expect(frame).toHaveBeenCalledTimes(2);nextFrame();await flushPromises();expect(mocks.render).toHaveBeenCalledTimes(2)
})
