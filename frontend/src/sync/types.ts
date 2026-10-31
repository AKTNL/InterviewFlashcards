export interface Source {
  id: string; originalFilename: string; pageCount: number; byteSize: number; createdAt: string; version: number
}
export interface Snapshot { dataEpoch: string; revision: number; sources: Source[] }
export interface WriteResult {
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
  kind: 'source.upload' | 'source.rename' | 'import.retry' | 'chapter.review' | 'card.update' | 'session.start' | 'review.write'; name: string; sourceId?: string; jobId?: string; cardId?: string; expectedVersion?: number; file?: Blob
  chapterId?: string; action?: string; payload?: Record<string, unknown>
  status: SyncStatus; attempts: number; nextAttemptAt: number
  error?: { code: string; message: string; details?: unknown; requestId?: string }; result?: WriteResult
}
export interface QueueStorage {
  all(): Promise<Mutation[]>
  put(item: Mutation): Promise<void>
  remove(operationId: string): Promise<void>
}
