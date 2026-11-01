import type { Heartbeat } from '../api/dashboard'

/** No offline queue or client duration totals. A discontinuity always creates a new stream. */
export class UsageTracker {
  private lastInteraction = -Infinity
  private previousTick: number | null = null
  private stream: string | null = null
  private sequence = 0
  private busy = false
  private disposed = false
  constructor(private env: { now(): number; eligible(): boolean; id(): string; send(body: Heartbeat): Promise<unknown> }) {}
  interact() { if (!this.disposed && this.env.eligible()) { this.lastInteraction = this.env.now(); if (!this.stream) void this.tick() } }
  async tick() {
    if (this.disposed) return
    const now = this.env.now()
    if (!this.env.eligible() || now - this.lastInteraction >= 300_000) { this.pause(); return }
    if (this.busy) return
    if (this.previousTick !== null && (now - this.previousTick > 45_000 || now < this.previousTick)) this.pause()
    if (this.previousTick !== null && now - this.previousTick < 30_000) return
    this.stream ??= this.env.id()
    const streamId = this.stream
    this.previousTick = now; this.busy = true
    try { await this.env.send({ streamId, sequence: ++this.sequence, active: true }) }
    catch { if (this.stream === streamId) this.pause() }
    finally { this.busy = false }
  }
  pause() {
    const streamId = this.stream
    this.stream = null; this.previousTick = null; this.sequence = 0
    if (streamId) void this.env.send({ streamId, sequence: 0, active: false }).catch(() => {})
  }
  suspend() { this.lastInteraction = -Infinity; this.pause() }
  dispose() { this.disposed = true; this.suspend() }
}
