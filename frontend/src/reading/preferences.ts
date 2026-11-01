export type ReadingTheme = 'light' | 'sepia' | 'dark'
export interface ReadingPreferences { fontSize: number; theme: ReadingTheme; shortcuts: boolean }
const defaults: ReadingPreferences = { fontSize: 18, theme: 'light', shortcuts: true }
export function readPreferences(userId: string): ReadingPreferences {
  try {
    const value = JSON.parse(localStorage.getItem(`reading.preferences.v1:${userId}`) ?? 'null')
    return { fontSize: [16, 18, 20, 22, 24].includes(value?.fontSize) ? value.fontSize : defaults.fontSize,
      theme: ['light', 'sepia', 'dark'].includes(value?.theme) ? value.theme : defaults.theme,
      shortcuts: typeof value?.shortcuts === 'boolean' ? value.shortcuts : defaults.shortcuts }
  } catch { return { ...defaults } }
}
export function savePreferences(userId: string, value: ReadingPreferences) {
  try { localStorage.setItem(`reading.preferences.v1:${userId}`, JSON.stringify(value)); return true } catch { return false }
}
