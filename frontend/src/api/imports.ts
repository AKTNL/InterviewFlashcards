import { request } from './client'
export interface CheckFlag { kind: string; reason: string; page?: number }
export interface ChapterCount { name: string; expectedCount: number; tocCount: number; candidateCount: number; recognitionDifference: number; baselineDifference: number }
export interface ImportJob {
  id: string; sourceId: string; status: 'QUEUED' | 'PARSING' | 'READY' | 'FAILED'; stage: string; progress: number
  errorCode: string | null; errorMessage: string | null; version: number; parserVersion: string
  report: { pageCount?: number; tocCount?: number; candidateCount?: number; expectedCount?: number; recognitionDifference?: number; baselineDifference?: number; chapters?: ChapterCount[]; missingChapters?: string[] }
}
export interface Chapter { id: string; name: string; originalName: string; module: string; checkStatus: string; candidateCount: number; flaggedCount: number; version?: number; abandoned?: boolean; checkedAt?: string; activeCount?: number }
export interface DraftSummary { id: string; question: string; sourceEntry: string; flags: CheckFlag[]; excluded: boolean; version: number }
export interface Draft extends DraftSummary {
  chapterId?: string; originalContent?: unknown; parentDraftId?: string | null
  sourceId: string; sources: { page: number; bbox: number[] }[]
  contentBlocks: { type: string; page: number; textSpans: { text: string; bbox: number[] }[]; images: { bbox: number[] }[] }[]
  followups: { title: string; page: number; text?: string }[]
}
export interface ChapterReview {
  id: string; name: string; version: number; checkStatus: string; abandoned: boolean; currentDraftId: string | null
  items: DraftSummary[]; cards: { id: string; draftId: string }[]; activeCount: number; flaggedCount: number; userCountDelta: number
  history: { action: string; reason: string; countDelta: number; createdAt: string; inputIds?: string[]; outputIds?: string[] }[]
}
export interface DraftPage { items: DraftSummary[]; offset: number; limit: number; total: number }
export const importsApi = {
  jobs: async (sourceId: string) => (await request<ImportJob[]>(`/api/import-jobs?sourceId=${sourceId}`)).data,
  job: async (id: string) => (await request<ImportJob>(`/api/import-jobs/${id}`)).data,
  chapters: async (id: string) => (await request<Chapter[]>(`/api/import-jobs/${id}/chapters`)).data,
  drafts: async (chapter: string, offset = 0) => (await request<DraftPage>(`/api/chapters/${chapter}/drafts?offset=${offset}&limit=20`)).data,
  draft: async (id: string) => (await request<Draft>(`/api/drafts/${id}`)).data,
  review: async (id: string) => (await request<ChapterReview>(`/api/chapters/${id}/review`)).data,
  cards: async (id: string) => (await request<Draft[]>(`/api/chapters/${id}/cards`)).data,
}
export const stageNames: Record<string, string> = { WAITING: '等待解析', EXTRACTING: '提取文字与来源位置', ANALYZING: '识别目录与问题边界', PERSISTING: '保存章节与草稿', COMPLETE: '草稿已生成' }
export const statusNames = { QUEUED: '等待解析', PARSING: '正在解析', READY: '草稿已就绪', FAILED: '解析失败' }
