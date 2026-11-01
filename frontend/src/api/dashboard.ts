import { request } from './client'
export interface UsageDay { day: string; active_users: number | null; learning_users: number; seconds: number | null; new_count: number; review_count: number }
export interface UsageMeta { timezone: string; from: string; to: string; updatedAt: string; collectionStartedAt: string; retainedFrom: string; activityCoverage: 'UNCOLLECTED' | 'PARTIAL' | 'COLLECTED' }
export interface Overview extends UsageMeta { summary: Record<string, number | null>; days: UsageDay[] }
export interface UsageUser { id: string; username: string; nickname: string | null; enabled: boolean; last_login_at: string | null; last_active_at: string | null; online: boolean; active_days: number | null; seconds: number | null; new_count: number; review_count: number; claims: number; storage_bytes: number }
export interface UsageUsers extends UsageMeta { items: UsageUser[]; total: number }
export interface UsageDetail extends UsageMeta { user: { id: string; username: string; nickname: string | null }; days: UsageDay[] }
export const overview = (params: URLSearchParams) => request<Overview>(`/api/admin/dashboard?${params}`, { cache: 'no-store' })
export const usageUsers = (params: URLSearchParams) => request<UsageUsers>(`/api/admin/dashboard/users?${params}`, { cache: 'no-store' })
export const usageDetail = (id: string, params: URLSearchParams) => request<UsageDetail>(`/api/admin/dashboard/users/${id}?${params}`, { cache: 'no-store' })
export interface Heartbeat { streamId: string; sequence: number; active: boolean }
export const heartbeat = (body: Heartbeat) => request<boolean>('/api/usage/heartbeat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), keepalive: !body.active })
