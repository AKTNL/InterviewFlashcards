export interface Source {
  id: string; originalFilename: string; pageCount: number; byteSize: number; createdAt: string; version: number
}
export interface Snapshot { dataEpoch: string; revision: number; sources: Source[] }
export interface WriteResult {
  source: Source
  duplicate?: boolean
  mutation: { operationId: string; dataEpoch: string; revision: number; entityVersion: number; serverTime: string }
}
export type SyncStatus = 'pending' | 'syncing' | 'synced' | 'failed' | 'needs-attention'
export interface Mutation {
  operationId: string; userId: string; dataEpoch: string; createdAt: number
  kind: 'source.upload' | 'source.rename'; name: string; sourceId?: string; expectedVersion?: number; file?: Blob
  status: SyncStatus; attempts: number; nextAttemptAt: number
  error?: { code: string; message: string; details?: unknown; requestId?: string }; result?: WriteResult
}
export interface QueueStorage {
  all(): Promise<Mutation[]>
  put(item: Mutation): Promise<void>
  remove(operationId: string): Promise<void>
}
