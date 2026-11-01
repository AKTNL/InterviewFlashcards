import { beforeEach, expect, it, vi } from 'vitest'
import { request } from './client'
import { libraryWrite } from './library'
vi.mock('./client', () => ({ request: vi.fn() }))
beforeEach(() => vi.clearAllMocks())
it('reuses the same operation after a lost response and separates changed requests', async () => {
  vi.mocked(request).mockRejectedValueOnce(new Error('network')).mockResolvedValue({ data: {}, meta: { requestId: 'r', serverTime: '' } })
  const path = '/api/library/stable/claim', body = { confirmed: true, contentVersion: 1, dataEpoch: 'epoch' }
  await expect(libraryWrite(path, body)).rejects.toThrow('network'); await libraryWrite(path, body)
  const calls = vi.mocked(request).mock.calls
  expect(JSON.parse(String(calls[0]![1]!.body)).operationId).toEqual(JSON.parse(String(calls[1]![1]!.body)).operationId)
  await libraryWrite(path, { ...body, contentVersion: 2 })
  expect(JSON.parse(String(calls[2]![1]!.body)).operationId).not.toEqual(JSON.parse(String(calls[0]![1]!.body)).operationId)
})
