import { expect, it, vi } from 'vitest'
import { UsageTracker } from './tracker'
function setup() {
  let now = 0, eligible = true, ids = 0
  const send = vi.fn().mockResolvedValue(true)
  const tracker = new UsageTracker({ now: () => now, eligible: () => eligible, id: () => `stream-${++ids}`, send })
  return { tracker, send, at: (value: number) => { now = value }, eligible: (value: boolean) => { eligible = value } }
}
it('requires activity, uses 30-second beats and stops at five minutes without sending duration or content', async () => {
  const { tracker, send, at } = setup()
  await tracker.tick(); expect(send).not.toHaveBeenCalled()
  tracker.interact(); await Promise.resolve()
  for (let t = 30_000; t <= 300_000; t += 30_000) { at(t); await tracker.tick() }
  expect(send.mock.calls.filter(c => c[0].active)).toHaveLength(10)
  expect(send.mock.calls.at(-1)![0]).toEqual({ streamId: 'stream-1', sequence: 0, active: false })
  expect(Object.keys(send.mock.calls[0]![0])).toEqual(['streamId', 'sequence', 'active'])
})
it('never joins intervals across hidden pages, network failure, sleep, identity changes or disposal', async () => {
  const { tracker, send, at, eligible } = setup()
  tracker.interact(); await Promise.resolve(); eligible(false); at(15_000); await tracker.tick()
  eligible(true); tracker.suspend(); at(30_000); await tracker.tick(); expect(send.mock.calls.filter(c => c[0].active)).toHaveLength(1)
  tracker.interact(); await Promise.resolve(); at(120_000); await tracker.tick() // delayed timer / wake
  const ids = send.mock.calls.filter(c => c[0].active).map(c => c[0].streamId)
  expect(new Set(ids).size).toBe(3)
  send.mockRejectedValueOnce(new Error('offline')); at(150_000); await tracker.tick(); at(180_000); await tracker.tick()
  expect(send.mock.calls.at(-1)![0].streamId).toBe('stream-4')
  tracker.dispose(); const calls = send.mock.calls.length; tracker.interact(); await tracker.tick(); expect(send).toHaveBeenCalledTimes(calls)
})
it('prevents overlapping slow requests and treats backward clock changes as a new baseline', async () => {
  const { tracker, send, at } = setup()
  let resolve!: () => void
  send.mockReturnValueOnce(new Promise<void>(r => { resolve = r }))
  tracker.interact(); at(30_000); await tracker.tick(); expect(send).toHaveBeenCalledTimes(1)
  resolve(); await Promise.resolve(); at(-100); await tracker.tick()
  expect(send.mock.calls.at(-1)![0]).toMatchObject({ streamId: 'stream-2', active: true })
})
it('accepts the next short timer check when the interval phase falls just before 30 seconds', async () => {
  const { tracker, send, at } = setup(); tracker.interact(); await Promise.resolve()
  at(29_000); await tracker.tick(); expect(send).toHaveBeenCalledTimes(1)
  at(34_000); await tracker.tick(); expect(send).toHaveBeenCalledTimes(2)
  expect(send.mock.calls.at(-1)![0]).toMatchObject({ streamId: 'stream-1', sequence: 2, active: true })
})
