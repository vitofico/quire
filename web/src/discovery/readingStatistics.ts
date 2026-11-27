import type { ReadingSession } from '../data'

const hour = 3_600
const day = 24 * hour
const dayMs = day * 1_000
const streakMinimum = 5 * 60

export function readingStatistics(sessions: readonly ReadingSession[], now = new Date()) {
  const today = Math.floor((now.getTime() - now.getTimezoneOffset() * 60_000) / dayMs)
  const daily = new Map<number, number>()
  const hours = Array<number>(24).fill(0)
  const weekdays = Array<number>(7).fill(0)
  const books = new Map<string, {
    title: string; latest: number; seconds: number; sessions: number; words: number; measuredSeconds: number
  }>()
  let total = 0
  for (const session of sessions) {
    total += session.seconds
    const book = books.get(session.bookId) ?? {
      title: session.title, latest: session.startedAt, seconds: 0, sessions: 0, words: 0, measuredSeconds: 0,
    }
    if (session.startedAt > book.latest) {
      book.title = session.title
      book.latest = session.startedAt
    }
    book.sessions++
    book.seconds += session.seconds
    if (session.words !== undefined) {
      book.words += session.words
      book.measuredSeconds += session.seconds
    }
    books.set(session.bookId, book)

    // Shift by the offset captured when reading, then split at local hour
    // boundaries. Travel does not move a session to a different day.
    let cursor = Math.floor(session.startedAt / 1000) + session.utcOffsetMinutes * 60
    let remaining = session.seconds
    while (remaining > 0) {
      const hourIndex = Math.floor(cursor / hour)
      const date = Math.floor(hourIndex / 24)
      const span = Math.min(remaining, (hourIndex + 1) * hour - cursor)
      daily.set(date, (daily.get(date) ?? 0) + span)
      hours[hourIndex - date * 24] += span
      weekdays[(new Date(date * dayMs).getUTCDay() + 6) % 7] += span
      cursor += span
      remaining -= span
    }
  }

  const streakDays = [...daily].filter(([, seconds]) => seconds >= streakMinimum)
    .map(([date]) => date).sort((a, b) => a - b)
  let run = 0
  let longest = 0
  let previous: number | undefined
  for (const date of streakDays) {
    run = date - 1 === previous ? run + 1 : 1
    longest = Math.max(longest, run)
    previous = date
  }
  const currentStreak = previous === today || previous === today - 1 ? run : 0

  let measuredSeconds = 0
  let words = 0
  for (const book of books.values()) {
    if (!book.measuredSeconds || book.words * 60 / book.measuredSeconds > 1500) continue
    measuredSeconds += book.measuredSeconds
    words += book.words
  }
  const pace = measuredSeconds >= 300 && words >= 500 ? Math.round(words * 60 / measuredSeconds) : undefined
  const recent = Array.from({length: 30}, (_, i) => {
    const date = today - 29 + i
    return {date: new Date(date * dayMs), seconds: daily.get(date) ?? 0}
  })
  const year = new Map<string, {date: Date; seconds: number}>()
  const month = new Date(today * dayMs)
  const monthKey = (date: Date) => date.toISOString().slice(0, 7)
  for (let i = 11; i >= 0; i--) {
    const date = new Date(Date.UTC(month.getUTCFullYear(), month.getUTCMonth() - i, 1))
    year.set(monthKey(date), {date, seconds: 0})
  }
  for (const [date, seconds] of daily) {
    const bucket = year.get(monthKey(new Date(date * dayMs)))
    if (bucket) bucket.seconds += seconds
  }
  const sum = (buckets: typeof recent) => buckets.reduce((seconds, bucket) => seconds + bucket.seconds, 0)
  const week = recent.slice(-7)
  return {
    today: daily.get(today) ?? 0, weekSeconds: sum(week), monthSeconds: sum(recent), total,
    currentStreak, longest, pace, week, month: recent, year: [...year.values()], hours, weekdays,
    books: [...books.entries()].sort(([, a], [, b]) => b.seconds - a.seconds || b.latest - a.latest),
  }
}
