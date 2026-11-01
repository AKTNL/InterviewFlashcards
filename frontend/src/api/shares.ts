import { request } from './client'
export interface ShareManifest {
  formatVersion: number; name: string; sender: string; capturedAt: string; sourceRevision: number; contentHash: string; snapshotId?: string
  counts: Record<string, number>; chapters: string[]
  sources: { name: string; pageCount: number; includedPages: string; sha256: string }[]
}
export interface SharePreview extends ShareManifest { dataEpoch: string; revision: number }
export interface ShareInvitation {
  id: string; outgoing: boolean; recipient: string; expiresAt: string; revoked: boolean; expired: boolean; claimCount: number; maxClaims: number; manifest: ShareManifest
}
export interface CreateShare {
  operationId: string; dataEpoch: string; deckId: string; expectedRevision: number; recipient: string; expiresInHours: number; rightsConfirmed: boolean
}
export interface ClaimShare { operationId: string; dataEpoch: string; confirmed: boolean }
const post = async <T>(path: string, body?: unknown) => (await request<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) })).data
export const sharesApi = {
  list: async () => (await request<{ outgoing: ShareInvitation[]; incoming: ShareInvitation[] }>('/api/shares')).data,
  preview: async (id: string) => (await request<SharePreview>(`/api/shares/decks/${id}/preview`)).data,
  create: (body: CreateShare) => post<{ share: ShareInvitation }>('/api/shares', body),
  claim: (id: string, body: ClaimShare) => post<{ deckId: string }>(`/api/shares/${id}/claim`, body),
  revoke: (id: string) => post<ShareInvitation>(`/api/shares/${id}/revoke`),
}
