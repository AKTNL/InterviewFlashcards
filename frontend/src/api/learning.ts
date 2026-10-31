import { request } from './client'
export interface ReviewState {
  firstLearnedAt: string; lastReviewedAt: string; dueDate: string; stage: number; latestRating: string; lapseCount: number; version: number
}
export interface SessionItem {
  unavailable?: boolean; skipReason?: string | null; itemKind?: string
  id: string; cardId: string; question: string; sortOrder: number; completed: boolean; skipped: boolean; isReplay: boolean; expectedVersion: number; currentVersion: number
}
export interface LearningSession {
  mode?: string
  round?: { newCount: number; reviewCount: number; attention: { id: string; question: string; latestRating: string; lapseCount: number }[] }
  id: string; version: number; status: string; learningDate: string; today: string; expired: boolean; canUndo: boolean; sameDayReplay: boolean
  items: SessionItem[]; current: SessionItem | null; review: ReviewState | null
  events: { id: string; cardId: string; kind: string; rating: string; isReplay: boolean; afterState: ReviewState }[]
}
export interface LearningPreferences {
  saved: boolean; version: number; dailyNewCount: number; reviewBatchSize: number; referenceMinutes: number | null; chapterIds: string[]
}
export interface TodayLearning {
  learningDate: string; learningTimezone: string; dueTotal: number; remainingNew: number; availableNew: number
  preferences: LearningPreferences; summary: { learned: number; todayNew: number; todayReview: number }
  chapters: { id: string; name: string; moduleName: string; deckName: string; selected: boolean; total: number; learned: number; due: number; weak: number }[]
  continuing: { id: string; mode: string; remaining: number }[]
}
export const learningModes: Record<string, string> = { MIXED: '混合学习', REVIEW: '只复习', NEW: '只学新题', MANUAL: '自主练习' }
export const learningApi = {
  session: async (id: string) => (await request<LearningSession>(`/api/learning-sessions/${id}`)).data,
  today: async () => (await request<TodayLearning>('/api/today')).data,
}
