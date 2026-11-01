import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { register, registrationEnabled } from '../api/auth'
import { ApiError } from '../api/client'
import RegisterView from './RegisterView.vue'
vi.mock('../api/auth', () => ({ register: vi.fn(), registrationEnabled: vi.fn() }))
afterEach(() => vi.resetAllMocks())
async function view() {
  vi.mocked(registrationEnabled).mockResolvedValue({ data: true, meta: { requestId: 'id', serverTime: 'now' } })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/register', component: RegisterView },{ path: '/login', component: { template: '<h1>登录</h1>' } }] })
  await router.push('/register')
  const wrapper = mount(RegisterView, { global: { plugins: [router] } }); await flushPromises()
  for(const [id,value] of [['invite','test-invite'],['new-username','alice'],['new-password','password-123456'],['confirm-password','password-123456']]) await wrapper.get(`#${id}`).setValue(value)
  return { wrapper, router }
}
it('clears password fields and guides the user to login after successful registration', async () => {
  vi.mocked(register).mockResolvedValue({ data: true, meta: { requestId: 'id', serverTime: 'now' } })
  const { wrapper, router } = await view(); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(register).toHaveBeenCalledOnce(); expect(router.currentRoute.value.fullPath).toBe('/login?registered=1')
  expect((wrapper.get('#new-password').element as HTMLInputElement).value).toBe('');wrapper.unmount()
})
it('does not replay an uncertain registration or allow a second submit', async () => {
  vi.mocked(register).mockRejectedValue(new ApiError('NETWORK_ERROR','连接失败',0))
  const { wrapper } = await view(); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain('请先尝试');expect(wrapper.find('form').exists()).toBe(false)
  expect(register).toHaveBeenCalledOnce();wrapper.unmount()
})
it('keeps mismatched passwords out of the API', async () => {
  const { wrapper } = await view();await wrapper.get('#confirm-password').setValue('different');await wrapper.get('form').trigger('submit')
  expect(register).not.toHaveBeenCalled();expect(wrapper.text()).toContain('两次密码不一致');wrapper.unmount()
})
