import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { register, registrationEnabled, validateInvite } from '../api/auth'
import { ApiError } from '../api/client'
import RegisterView from './RegisterView.vue'
vi.mock('../api/auth', () => ({ register: vi.fn(), registrationEnabled: vi.fn(), validateInvite: vi.fn() }))
afterEach(() => vi.resetAllMocks())
async function view() {
  vi.mocked(registrationEnabled).mockResolvedValue({ data: true, meta: { requestId: 'id', serverTime: 'now' } })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/register', component: RegisterView },{ path: '/login', component: { template: '<h1>登录</h1>' } }] })
  await router.push('/register')
  const wrapper = mount(RegisterView, { global: { plugins: [router] } }); await flushPromises()
  vi.mocked(validateInvite).mockResolvedValue({ data: true, meta: { requestId: 'id', serverTime: 'now' } })
  await wrapper.get('#invite').setValue('test-invite');await wrapper.get('form').trigger('submit');await flushPromises()
  for(const [id,value] of [['new-username','alice'],['new-password','password-123456'],['confirm-password','password-123456']]) await wrapper.get(`#${id}`).setValue(value)
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
it('revalidates on final failure and preserves only non-sensitive account inputs', async () => {
  vi.mocked(register).mockRejectedValue(new ApiError('INVITE_INVALID','邀请码不可用',400))
  const { wrapper } = await view();await wrapper.get('#nickname').setValue('朋友');await wrapper.get('form').trigger('submit');await flushPromises()
  expect(wrapper.find('#invite').exists()).toBe(true);expect(wrapper.find('#new-password').exists()).toBe(false)
  await wrapper.get('#invite').setValue('replacement');await wrapper.get('form').trigger('submit');await flushPromises()
  expect((wrapper.get('#new-username').element as HTMLInputElement).value).toBe('alice')
  expect((wrapper.get('#nickname').element as HTMLInputElement).value).toBe('朋友')
  expect((wrapper.get('#new-password').element as HTMLInputElement).value).toBe('');wrapper.unmount()
})
it('does not show account setup before anonymous invite validation succeeds', async () => {
  vi.mocked(registrationEnabled).mockResolvedValue({data:true,meta:{requestId:'id',serverTime:'now'}})
  vi.mocked(validateInvite).mockRejectedValue(new ApiError('INVITE_INVALID','邀请码不可用',400))
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/register',component:RegisterView}]});await router.push('/register')
  const wrapper=mount(RegisterView,{global:{plugins:[router]}});await flushPromises()
  expect(wrapper.find('#new-password').exists()).toBe(false)
  await wrapper.get('#invite').setValue('bad');await wrapper.get('form').trigger('submit');await flushPromises()
  expect(wrapper.find('#new-password').exists()).toBe(false);expect(register).not.toHaveBeenCalled();wrapper.unmount()
})
