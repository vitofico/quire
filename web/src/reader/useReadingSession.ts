import { useEffect, useRef } from 'react'
import type { ReadingRecording, ReadingSession, Replica } from '../data'

const minimum = 30_000
const idleAfter = 180_000
const chapterWords = new WeakMap<HTMLElement, number>()
const eastAsianCharacters = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]/gu

/** An estimate from one reflowable chapter, spread across the views it occupies. */
export function wordsInView(doc: Document, views: number): number | undefined {
  if (doc.documentElement.hasAttribute('data-quire-fixed')) return
  let count = chapterWords.get(doc.body)
  if (count === undefined) {
    const text = doc.body.innerText || doc.body.textContent || ''
    const characters = text.match(eastAsianCharacters)?.length ?? 0
    const otherWords = text.replace(eastAsianCharacters, ' ').match(/[\p{L}\p{N}]+/gu)?.length ?? 0
    count = characters + otherWords
    chapterWords.set(doc.body, count)
  }
  return count ? count / views : undefined
}

type Activity = (estimate?: () => number | undefined) => void

export function useReadingSession(storage: Replica, bookId: string, title: string, ready: boolean): Activity {
  const activity = useRef<Activity | undefined>(undefined)
  useEffect(() => {
    if (!ready) return
    let recording: ReadingRecording | undefined
    let session: { row: ReadingSession; start: number; lastActivity: number } | undefined
    let released = false

    function start(now = performance.now()) {
      if (session || !recording?.enabled || document.hidden) return
      const date = new Date()
      session = {start: now, lastActivity: now, row: {
        id: crypto.randomUUID(), bookId, title, startedAt: date.getTime(),
        utcOffsetMinutes: -date.getTimezoneOffset(), seconds: 0,
      }}
    }

    function save(now: number, final: boolean) {
      if (!session || !recording) return
      const idle = now - session.lastActivity >= idleAfter
      // Leave a short checkpoint for abruptly closed tabs. A page turn or a
      // normal exit includes the intervening time while the reader was active.
      const end = idle || !final ? Math.min(now, session.lastActivity + minimum) : now
      const seconds = Math.floor((end - session.start) / 1000)
      if (seconds >= minimum / 1000) {
        void storage.saveReadingSession({...session.row, seconds}, recording.generation).catch(() => {})
      }
      if (final || idle) session = undefined
    }

    const finish = () => save(performance.now(), true)
    const visibility = () => document.hidden ? finish() : start()
    void storage.readReadingRecording().then(value => {
      if (released) return
      recording = value
      start()
    }).catch(() => {})
    activity.current = estimate => {
      if (document.hidden) return
      const now = performance.now()
      if (session && now - session.lastActivity >= idleAfter) save(now, true)
      start(now)
      if (!session) return
      session.lastActivity = now
      const words = estimate?.()
      if (words !== undefined) session.row.words = (session.row.words ?? 0) + words
    }
    const timer = window.setInterval(() => save(performance.now(), false), minimum)
    document.addEventListener('visibilitychange', visibility)
    window.addEventListener('pagehide', finish)
    window.addEventListener('pageshow', visibility)
    return () => {
      released = true
      activity.current = undefined
      clearInterval(timer)
      document.removeEventListener('visibilitychange', visibility)
      window.removeEventListener('pagehide', finish)
      window.removeEventListener('pageshow', visibility)
      finish()
    }
  }, [storage, bookId, title, ready])
  return estimate => activity.current?.(estimate)
}
