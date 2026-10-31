import { request } from './client'
import type { Draft } from './imports'
export interface CatalogEntry { deckId: string; deckName: string; moduleId: string; moduleName: string; chapterId: string; chapterName: string; checkStatus: string; cardCount: number }
export interface CardSummary { id: string; question: string; chapterName: string; moduleName: string; needsCheck: boolean; favorite: boolean; learned: boolean; dueDate: string | null; latestRating: string | null }
export interface Annotation { notes: string; hint: string; memoryPoints: string; favorite: boolean; version: number }
export interface CardDetail extends Omit<Draft, 'sources' | 'sourceId' | 'sourceEntry' | 'excluded'> {
  draftId: string; chapterId: string; chapterVersion: number; chapterName: string; moduleName: string; deckName: string
  parentCardId: string | null; needsCheck: boolean; displayRevision: number; applicableVersion: string | null; technicalCorrection: string; tags: string[]
  annotation: Annotation; review: { learned: boolean; dueDate: string | null; latestRating: string | null; lapseCount: number | null }
  sources: { sourceId: string; page: number; bbox: number[]; name: string }[]
}
export interface CardPage { items: CardSummary[]; offset: number; limit: number; total: number; learningDate: string }
export const cardsApi = {
  catalog: async () => (await request<CatalogEntry[]>('/api/cards/catalog')).data,
  list: async (query: URLSearchParams) => (await request<CardPage>(`/api/cards?${query}`)).data,
  detail: async (id: string) => (await request<CardDetail>(`/api/cards/${id}`)).data,
}
export const filters: Record<string, string> = { ALL: '全部', UNLEARNED: '未学', LEARNED: '已学', DUE: '到期', WEAK: '薄弱', FAVORITE: '收藏', CHECK: '待检查' }
export const ratings: Record<string, string> = { FORGOT: '不会', VAGUE: '模糊', REMEMBERED: '记住了' }
