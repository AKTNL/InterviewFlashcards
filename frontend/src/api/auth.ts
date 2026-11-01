import { request } from './client'

export interface Profile {
  id: string
  username: string
  nickname?: string | null
  learningTimezone: string
  dataEpoch: string
  revision: number
}

export const getMe = () => request<Profile>('/api/auth/me')
export const login = (username: string, password: string) => request<Profile>('/api/auth/login', {
  method: 'POST', body: new URLSearchParams({ username, password }),
})
export const logout = () => request<boolean>('/api/auth/logout', { method: 'POST' })
export const registrationEnabled = () => request<boolean>('/api/auth/registration')
export const register = (invite: string, username: string, password: string, nickname: string) => request<boolean>('/api/auth/register', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ invite, username, password, nickname }),
})
export const changePassword = (currentPassword: string, newPassword: string) => request<boolean>('/api/auth/password', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ currentPassword, newPassword }),
})
