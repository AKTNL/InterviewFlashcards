export interface Source {
  id: string; originalFilename: string; pageCount: number; byteSize: number; createdAt: string; version: number
}
export interface ReviewConflict {
  id: string; cardId: string; dataEpoch: string; operationId: string; version: number; resolved: boolean; resolution?: string
  local: { sessionId: string; payload: { rating: string } }
  server: { eventId: string; rating: string; learningDate: string; afterState: import('../api/learning').ReviewState }
}
export interface Snapshot { dataEpoch: string; revision: number; learningDate?: string; sources: Source[]; data?: Record<string, Record<string, unknown>[]>; changedTables?: string[] }
export interface SyncChanges {
  dataEpoch: string; revision: number; learningDate: string; fromRevision: number; fullRequired: boolean; reason?: string
  changes?: { table: string; key: Record<string, string | number>; row: Record<string, unknown> | null }[]
}
export interface WriteResult {
  action?: string; impact?: { deckId: string }
  conflict?: ReviewConflict; duplicateScore?: boolean
  preferences?: import('../api/learning').LearningPreferences
  session?: import('../api/learning').LearningSession
  eventId?: string; dueDate?: string; isReplay?: boolean
  source?: Source
  job?: { id: string; version: number }
  chapter?: { id: string; version: number }
  card?: { id: string; version: number }
  duplicate?: boolean
  mutation: { operationId: string; dataEpoch: string; revision: number; entityVersion: number; serverTime: string }
}
export type SyncStatus = 'pending' | 'syncing' | 'synced' | 'failed' | 'needs-attention'
export interface Mutation {
  operationId: string; userId: string; dataEpoch: string; createdAt: number
  kind: 'deck.data' | 'source.upload' | 'source.rename' | 'import.retry' | 'chapter.review' | 'card.update' | 'session.start' | 'review.write' | 'preferences.save' | 'session.daily' | 'conflict.resolve'; name: string; sourceId?: string; jobId?: string; cardId?: string; expectedVersion?: number; file?: Blob
  chapterId?: string; action?: string; payload?: Record<string, unknown>
  status: SyncStatus; attempts: number; nextAttemptAt: number
  error?: { code: string; message: string; details?: unknown; requestId?: string }; result?: WriteResult
}
export interface QueueStorage {
  all(): Promise<Mutation[]>
  put(item: Mutation): Promise<void>
  remove(operationId: string): Promise<void>
}
