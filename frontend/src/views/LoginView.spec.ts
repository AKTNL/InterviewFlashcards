import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it, vi } from 'vitest'
import { useAuthStore } from '../stores/auth'
import LoginView from './LoginView.vue'

it('waits for the old logout to finish before exposing the next account login form', async () => {
  const pinia=createPinia();setActivePinia(pinia)
  const auth=useAuthStore();auth.endingSession=true
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/login',component:LoginView}]});await router.push('/login')
  const wrapper=mount(LoginView,{global:{plugins:[pinia,router]}})
  expect(wrapper.find('form').exists()).toBe(false);expect(wrapper.text()).toContain('正在退出原账号')
  auth.endingSession=false;await wrapper.vm.$nextTick()
  expect(wrapper.find('form').exists()).toBe(true);wrapper.unmount()
})


it('routes a temporary-password login directly to password change', async () => {
  const pinia=createPinia();setActivePinia(pinia);const auth=useAuthStore()
  vi.spyOn(auth,'signIn').mockImplementation(async()=>{auth.user={id:'new',username:'friend',learningTimezone:'Asia/Shanghai',dataEpoch:'e',revision:0,mustChangePassword:true}})
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/login',component:LoginView},{path:'/account',component:{template:'<p>改密</p>'}},{path:'/sources',component:{template:'<p>资料</p>'}}]});await router.push('/login')
  const wrapper=mount(LoginView,{global:{plugins:[pinia,router]}})
  await wrapper.get('#username').setValue('friend');await wrapper.get('#password').setValue('test-initial-password');await wrapper.get('form').trigger('submit');await flushPromises()
  expect(router.currentRoute.value.path).toBe('/account');wrapper.unmount();vi.restoreAllMocks()
})
