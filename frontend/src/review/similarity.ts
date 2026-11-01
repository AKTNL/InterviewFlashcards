import type { DraftSummary } from '../api/imports'

// Title overlap is a navigation hint, never evidence that two answers can be merged.
export function normalizeTitle(title: string): string {
  return title.normalize('NFKC').toLowerCase()
    .replace(/^\s*(?:第\s*\d+\s*题\s*[:：、.]?|\d+\s*[.、)）:：])\s*/u, '')
    .replace(/[\p{P}\p{Z}\p{S}\s]/gu, '')
}

function pairs(title: string): Map<string, number> {
  const counts = new Map<string, number>(), chars = Array.from(title)
  for (let i = 1; i < chars.length; i++) {
    const pair = chars[i - 1]! + chars[i]!
    counts.set(pair, (counts.get(pair) ?? 0) + 1)
  }
  return counts
}

export function titleSimilarity(left: string, right: string): number {
  const a = normalizeTitle(left), b = normalizeTitle(right)
  if (Array.from(a).length < 2 || Array.from(b).length < 2) return 0
  if (a === b) return 1
  if (Array.from(a).length < 4 || Array.from(b).length < 4) return 0
  const aa = pairs(a), bb = pairs(b)
  let overlap = 0
  for (const [pair, count] of aa) overlap += Math.min(count, bb.get(pair) ?? 0)
  return 2 * overlap / (Array.from(a).length + Array.from(b).length - 2)
}

export function similarDrafts(current: DraftSummary, items: DraftSummary[]) {
  if (current.excluded) return []
  return items.filter(item => item.id !== current.id && !item.excluded)
    .map(item => ({ item, score: titleSimilarity(current.question, item.question) }))
    .filter(match => match.score >= 0.65)
    .sort((a, b) => b.score - a.score || a.item.id.localeCompare(b.item.id))
}
