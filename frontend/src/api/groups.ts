import { request } from './client'

export interface GroupSummary {
  learningDate: string; learningZone: string; studiedToday: boolean
  todayNew: number; todayReview: number; weekLearningDays: number
}
export interface GroupInvite {
  id: string; groupId: string; name: string; inviter: string; expiresAt: string
}
export interface GroupDetail {
  id: string; name: string; owner: boolean; sharing: boolean; version: number
  members: { nickname: string; self: boolean; sharing: boolean; summary?: GroupSummary }[]
  invites?: { id: string; recipient: string; expiresAt: string; accepted: boolean; revoked: boolean; expired: boolean }[]
}
export interface GroupList { groups: { id: string; name: string; owner: boolean }[]; incoming: GroupInvite[] }
const post = async <T>(path: string, body: unknown = {}) => (await request<T>(path, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})).data
export const groupsApi = {
  list: async () => (await request<GroupList>('/api/groups')).data,
  detail: async (id: string) => (await request<GroupDetail>(`/api/groups/${id}`)).data,
  create: (command: { id: string; name: string; confirmed: boolean }) => post<{ id: string }>('/api/groups', command),
  invite: (id: string, command: { id: string; recipient: string; expiresInHours: number }) => post(`/api/groups/${id}/invites`, command),
  revoke: (id: string, invite: string) => post(`/api/groups/${id}/invites/${invite}/revoke`),
  join: (id: string, invite: string) => post(`/api/groups/${id}/join/${invite}`, { confirmed: true }),
  consent: (id: string, expectedVersion: number, sharing: boolean) => post(`/api/groups/${id}/consent`, { expectedVersion, sharing }),
  leave: (id: string) => post(`/api/groups/${id}/leave`),
}
