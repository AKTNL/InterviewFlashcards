import { request } from './client'

export interface Manifest {
  formatVersion: number
  createdAt: string
  counts: Record<string, number>
  algorithmVersions: string[]
  files: { path: string; byteSize: number; sha256: string }[]
}
export interface BackupJob {
  id: string
  status: 'QUEUED' | 'RUNNING' | 'VALIDATING' | 'AWAITING_CONFIRMATION' | 'RESTORING' | 'READY' | 'FAILED'
  progress: number
  created_at: string
  error_code: string | null
  manifest?: Manifest
  validation_result?: { manifest: Manifest; scope: string; currentCounts: Record<string, number>; expectedEpoch: string; expectedRevision: number; completedAt?: string }
}
const post = <T>(path: string, value: unknown) => request<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) }).then(r => r.data)
export const backupsApi = {
  exports: () => request<BackupJob[]>('/api/backups').then(r => r.data),
  restores: () => request<BackupJob[]>('/api/restores').then(r => r.data),
  export: (operationId: string) => post<BackupJob>('/api/backups', { operationId }),
  upload: (file: File, operationId: string) => {
    const form = new FormData(); form.append('file', file); form.append('operationId', operationId)
    return request<BackupJob>('/api/restores', { method: 'POST', body: form }).then(r => r.data)
  },
  preview: (id: string) => post<BackupJob>(`/api/restores/${id}/preview`, {}),
  confirm: (job: BackupJob) => post<BackupJob>(`/api/restores/${job.id}/confirm`, {
    dataEpoch: job.validation_result!.expectedEpoch, expectedRevision: job.validation_result!.expectedRevision, confirmed: true,
  }),
}
