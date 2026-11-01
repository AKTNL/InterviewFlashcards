import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it } from 'vitest'
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
