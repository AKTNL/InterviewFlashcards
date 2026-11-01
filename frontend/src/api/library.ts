import { request } from './client'
import type { Chapter, ChapterReview, Draft, ImportJob } from './imports'
import type { ShareManifest } from './shares'
export interface LibraryEntry {
  id: string; name: string; description: string; status: string; version: number; revision: number; content_version: number
  samples?: { question: string; answer: string }[]
  counts: { chapters: number; cards: number; activeTasks: number; claims: number }; manifest: ShareManifest
}
export interface LibraryClaim { library_id: string; deck_id: string; content_version: number }
// Keep identical online attempts stable across a lost response. Server receipts validate the full payload.
const attempts = new Map<string, string>()
export async function libraryWrite<T>(path: string, payload: Record<string, unknown>) {
  const signature = JSON.stringify([path, payload])
  let operationId = attempts.get(signature)
  if (!operationId) { operationId = crypto.randomUUID(); attempts.set(signature, operationId) }
  const result = await request<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...payload, operationId }) })
  attempts.delete(signature)
  return result.data
}
export const libraryApi = {
  catalog: async (page = 0) => (await request<LibraryEntry[]>(`/api/library?page=${page}&size=20`)).data,
  claims: async () => (await request<LibraryClaim[]>('/api/library/claims')).data,
  list: async () => (await request<LibraryEntry[]>('/api/admin/library')).data,
  preview: async (id: string) => (await request<LibraryEntry>(`/api/admin/library/${id}/preview`)).data,
  jobs: async (id: string) => (await request<ImportJob[]>(`/api/admin/library/${id}/jobs`)).data,
  chapters: async (id: string, job: string) => (await request<Chapter[]>(`/api/admin/library/${id}/jobs/${job}/chapters`)).data,
}
export function libraryReviewApi(id: string) {
  return {
    review: async (chapter: string) => (await request<ChapterReview>(`/api/admin/library/${id}/chapters/${chapter}/review`)).data,
    draft: async (draft: string) => (await request<Draft>(`/api/admin/library/${id}/drafts/${draft}`)).data,
  }
}
