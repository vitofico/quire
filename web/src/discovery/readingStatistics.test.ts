import { expect, it } from 'vitest'
import { readingStatistics } from './readingStatistics'

it('groups reading by its original local time and splits midnight across days', () => {
  const stats = readingStatistics([{
    id: '1', bookId: 'a', title: 'Book A',
    startedAt: Date.UTC(2026, 0, 1, 22, 58), utcOffsetMinutes: 60, seconds: 420, words: 1400,
  }], new Date(2026, 0, 2, 12))
  expect(stats.today).toBe(300)
  expect(stats.week.at(-2)?.seconds).toBe(120)
  expect(stats.hours[23]).toBe(120)
  expect(stats.hours[0]).toBe(300)
  expect(stats.currentStreak).toBe(1)
  expect(stats.pace).toBe(200)
})

it('keeps skim time and excludes its implausible pace', () => {
  const base = {id: '1', bookId: 'a', title: 'Book A', startedAt: Date.UTC(2026, 0, 2, 12), utcOffsetMinutes: 0}
  const stats = readingStatistics([
    {...base, seconds: 300, words: 1000},
    {...base, id: '2', bookId: 'skim', seconds: 300, words: 60_000},
  ], new Date(2026, 0, 2, 12))
  expect(stats.total).toBe(600)
  expect(stats.pace).toBe(200)
})
