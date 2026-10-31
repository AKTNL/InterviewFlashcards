import { request } from './client'

export interface Health {
  status: 'UP'
  database: 'UP'
  learningTimezone: string
  learningDate: string
}

export function getHealth() {
  return request<Health>('/api/health')
}
