import { request } from './client'
export interface ReviewState {
  firstLearnedAt: string; lastReviewedAt: string; dueDate: string; stage: number; latestRating: string; lapseCount: number; version: number
}
export interface SessionItem {
  id: string; cardId: string; question: string; sortOrder: number; completed: boolean; skipped: boolean; isReplay: boolean; expectedVersion: number; currentVersion: number
}
export interface LearningSession {
  id: string; version: number; status: string; learningDate: string; today: string; expired: boolean; canUndo: boolean; sameDayReplay: boolean
  items: SessionItem[]; current: SessionItem | null; review: ReviewState | null
  events: { id: string; cardId: string; kind: string; rating: string; isReplay: boolean; afterState: ReviewState }[]
}
export const learningApi = {
  session: async (id: string) => (await request<LearningSession>(`/api/learning-sessions/${id}`)).data,
}
