import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { getHealth } from '../api/health'
import HomeView from './HomeView.vue'

vi.mock('../api/health', () => ({ getHealth: vi.fn() }))

it('shows service failure, trace and a working retry action', async () => {
  vi.mocked(getHealth).mockRejectedValueOnce(new ApiError('SERVICE_UNAVAILABLE', '服务暂时不可用', 503, 'trace-test'))
    .mockResolvedValueOnce({
      data: { status: 'UP', database: 'UP', learningDate: '2026-10-31', learningTimezone: 'Asia/Shanghai' },
      meta: { requestId: 'trace-ok', serverTime: '2026-10-31T00:00:00Z' },
    })
  const wrapper = mount(HomeView, { global: { plugins: [createPinia()] } })
  await flushPromises()
  expect(wrapper.find('[role="status"]').text()).toContain('服务暂时不可用')
  expect(wrapper.text()).toContain('trace-test')
  expect(wrapper.get('button').text()).toBe('重新连接')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.find('[role="status"]').text()).toContain('服务与数据库连接正常')
  expect(wrapper.text()).toContain('2026-10-31')
  expect(wrapper.text()).not.toContain('trace-test')
})
