import { request } from './client'
export interface AdminUser {
  id: string; username: string; nickname: string | null; role: 'USER' | 'ADMIN'; enabled: boolean
  created_at: string; last_login_at: string | null; account_origin: string; must_change_password: boolean; admin_version: number
  invitation_enabled: boolean; total_limit: number; successful_count: number; remaining_quota: number
  storage: { usedBytes: number; maxBytes: number; sources: number }
}
export interface AdminDetail extends AdminUser {
  invites: { id: string; expires_at: string; used_count: number; max_uses: number; revoked: boolean; status: string }[]
  audit: { id: string; actor_id: string | null; actor_source: string; action: string; before_value: string; after_value: string; result: string; created_at: string }[]
}
export interface Configuration {
  registrationEnabled: boolean; maxAccounts: number; allocatedAccounts: number; configurationChannel: string
  capacity: { userBytes: number; sources: number; imports: number; backups: number; globalTasks: number; globalBytes: number; reserveBytes: number }
}
export const getAdminUsers = (params: URLSearchParams) => request<{ items: AdminUser[]; total: number }>(`/api/admin/users?${params}`, { cache: 'no-store' })
export const getAdminDetail = (id: string) => request<AdminDetail>(`/api/admin/users/${id}`, { cache: 'no-store' })
export const getAdminConfiguration = () => request<Configuration>('/api/admin/configuration', { cache: 'no-store' })
export const adminWrite = <T = boolean>(path: string, body: unknown) => request<T>(`/api/admin/${path}`, { method: 'POST', cache: 'no-store', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
