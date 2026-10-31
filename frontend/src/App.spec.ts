import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, onBeforeRouteLeave } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { useAuthStore } from './stores/auth'
import { useSyncStore } from './stores/sync'
import App from './App.vue'
const wrappers: { unmount(): void }[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount());vi.restoreAllMocks() })
async function view() {
  const pinia = createPinia();setActivePinia(pinia);const auth = useAuthStore(),sync = useSyncStore()
  auth.user = { id: 'u', username: 'test', learningTimezone: 'Asia/Shanghai', dataEpoch: 'epoch', revision: 0 }
  vi.spyOn(auth, 'refresh').mockResolvedValue(undefined);vi.spyOn(sync, 'connect').mockResolvedValue(undefined)
  const signOut = vi.spyOn(auth, 'signOut').mockResolvedValue(undefined),leave = vi.fn(() => true)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { template: '<h1>编辑中</h1>', setup() { onBeforeRouteLeave(leave) } } },
    { path: '/login', component: { template: '<h1>登录</h1>' } },
  ] });await router.push('/');await router.isReady()
  const wrapper = mount(App, { global: { plugins: [pinia,router], stubs: { SyncPanel: true } } });wrappers.push(wrapper);await flushPromises()
  const exit = () => wrapper.findAll('button').find(b => b.text() === '退出')!.trigger('click')
  return { wrapper,auth,sync,signOut,leave,router,exit }
}
it('runs the editor leave guard before ending the login session', async () => {
  const { signOut,leave,router,exit } = await view();leave.mockReturnValue(false);await exit();await flushPromises()
  expect(leave).toHaveBeenCalled();expect(signOut).not.toHaveBeenCalled();expect(router.currentRoute.value.path).toBe('/')
})
it('requires acknowledgement of unresolved sync writes before leaving', async () => {
  const { sync,signOut,exit } = await view();sync.items = [{ kind: 'review.write', name: '评分', operationId: 'op', userId: 'u', dataEpoch: 'epoch', createdAt: 1, status: 'pending', attempts: 0, nextAttemptAt: 0 }]
  vi.spyOn(window, 'confirm').mockReturnValue(false);await exit();await flushPromises();expect(signOut).not.toHaveBeenCalled()
})
it('shows a failed sign-out and retains the account instead of claiming success', async () => {
  const { wrapper,auth,signOut,exit } = await view();signOut.mockRejectedValueOnce(new Error('network failed'));await exit();await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('退出失败');expect(auth.user?.id).toBe('u')
})
