import { request } from './client'
export interface ReviewPolicy {
  version: number; algorithmVersion: string; intervals: number[]; reason: string; createdAt: string | null
}
export interface ReviewReport {
  current: ReviewPolicy; history: ReviewPolicy[]; from: string; to: string; learningTimezone: string; generatedAt: string
  activity: { algorithmVersion: string; assessments: number; firstAssessments: number; replays: number }[]
  outcomes: { algorithmVersion: string; parameters: Partial<ReviewPolicy>; observations: number; cards: number; forgot: number; vague: number; remembered: number; onTime: number; late: number; averageLateDays: number; averagePlannedDays: number }[]
}
export interface SaveReviewParameters {
  operationId: string; dataEpoch: string; expectedVersion: number; intervals: number[]; reason: string
}
export const reviewParametersApi = {
  report: async (days = 90) => (await request<ReviewReport>(`/api/review-parameters?days=${days}`)).data,
  save: async (body: SaveReviewParameters) => (await request<{ current: ReviewPolicy }>('/api/review-parameters', { method: 'POST', body: JSON.stringify(body) })).data,
}
