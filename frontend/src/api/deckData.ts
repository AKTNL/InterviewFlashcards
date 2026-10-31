import { request } from './client'
export interface DeckImpact {
  deckId: string; name: string; dataEpoch: string; revision: number
  counts: { cards: number; drafts: number; annotations: number; learned: number; events: number; sessions: number }
}
export const deckDataApi = {
  decks: async () => (await request<{ id: string; name: string }[]>('/api/deck-data')).data,
  preview: async (id: string) => (await request<DeckImpact>(`/api/deck-data/${id}/preview`)).data,
}
