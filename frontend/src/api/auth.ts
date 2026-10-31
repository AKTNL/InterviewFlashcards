import { request } from './client'

export interface Profile {
  id: string
  username: string
  learningTimezone: string
  dataEpoch: string
  revision: number
}

export const getMe = () => request<Profile>('/api/auth/me')
export const login = (username: string, password: string) => request<Profile>('/api/auth/login', {
  method: 'POST', body: new URLSearchParams({ username, password }),
})
export const logout = () => request<boolean>('/api/auth/logout', { method: 'POST' })
