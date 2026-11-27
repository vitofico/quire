import { useEffect, useId, useMemo, useState } from 'react'
import type { ReadingRecording, ReadingSession, Replica } from '../data'
import { ask } from '../ask'
import { readingStatistics } from './readingStatistics'
import { interpolate, type Translate } from './translation'

function formatDuration(seconds: number, locale: string) {
  const unit = (value: number, name: string) => new Intl.NumberFormat(locale, {
    style: 'unit', unit: name,
  }).format(value)
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor(seconds / 60) % 60
  if (hours) return [unit(hours, 'hour'), minutes ? unit(minutes, 'minute') : ''].filter(Boolean).join(' ')
  return minutes ? unit(minutes, 'minute') : unit(seconds, 'second')
}

type Bucket = {label: string; seconds: number}

function ActivityChart({title, buckets, duration, t}: {
  title: string; buckets: Bucket[]; duration: (seconds: number) => string; t: Translate
}) {
  const max = Math.max(1, ...buckets.map(bucket => bucket.seconds))
  return <figure className="reading-chart">
    <figcaption>{title}</figcaption>
    <div className="reading-chart-bars" aria-hidden="true">
      {buckets.map(({label, seconds}) => <span key={label} title={`${label} · ${duration(seconds)}`}>
        <span style={{height: `${seconds / max * 100}%`}} />
      </span>)}
    </div>
    <div className="reading-chart-range hint" aria-hidden="true">
      <span>{buckets[0].label}</span><span>{buckets[buckets.length - 1].label}</span>
    </div>
    <details>
      <summary>{t('Show values')}</summary>
      <dl className="reading-chart-values">
        {buckets.map(({label, seconds}) => <div key={label}><dt>{label}</dt><dd>{duration(seconds)}</dd></div>)}
      </dl>
    </details>
  </figure>
}

export function ReadingStatisticsPanel({storage, t}: {storage: Replica; t: Translate}) {
  const heading = useId()
  const [recording, setRecording] = useState<ReadingRecording>()
  const [sessions, setSessions] = useState<ReadingSession[]>([])
  const [period, setPeriod] = useState<'week' | 'month' | 'year'>('week')
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let alive = true
    void storage.readingStatistics().then(([preference, rows]) => {
      if (alive) {
        setRecording(preference)
        setSessions(rows)
      }
    }, () => { if (alive) setFailed(true) })
    return () => { alive = false }
  }, [storage])

  async function update(changes: {enabled?: boolean; clear?: boolean}) {
    const previous = recording
    if (previous && changes.enabled !== undefined) {
      setRecording({...previous, enabled: changes.enabled})
    }
    setBusy(true)
    setFailed(false)
    try {
      setRecording(await storage.updateReadingRecording(changes))
      if (changes.clear) setSessions([])
    } catch {
      setRecording(previous)
      setFailed(true)
    } finally {
      setBusy(false)
    }
  }

  async function clear() {
    if (await ask(t('Clear the reading statistics stored in this browser?'), {
      confirm: t('Clear statistics'), cancel: t('Cancel'), destructive: true,
    })) await update({clear: true})
  }

  const locale = document.documentElement.lang || 'en'
  const stats = useMemo(() => readingStatistics(sessions), [sessions])
  const duration = (seconds: number) => formatDuration(seconds, locale)
  const dayCount = new Intl.NumberFormat(locale, {style: 'unit', unit: 'day'})
  const dateLabel = new Intl.DateTimeFormat(locale, period === 'year'
    ? {month: 'short', year: 'numeric', timeZone: 'UTC'}
    : {month: 'short', day: 'numeric', timeZone: 'UTC'})
  const weekdayLabel = new Intl.DateTimeFormat(locale, {weekday: 'short', timeZone: 'UTC'})
  const hourLabel = new Intl.DateTimeFormat(locale, {hour: 'numeric', timeZone: 'UTC'})
  const metrics: [string, string][] = [
    ['Today', duration(stats.today)], ['Last 7 days', duration(stats.weekSeconds)],
    ['Last 30 days', duration(stats.monthSeconds)], ['Total time', duration(stats.total)],
    ['Current streak', dayCount.format(stats.currentStreak)], ['Longest streak', dayCount.format(stats.longest)],
  ]
  if (stats.pace !== undefined) {
    metrics.push(['Estimated pace', interpolate(t, '{count} words/min', {count: stats.pace.toLocaleString(locale)})])
  }
  return <section className="discovery-panel panel reading-statistics" aria-labelledby={heading}>
    <h3 id={heading}>{t('Reading statistics')}</h3>
    <p className="hint">{t('Reading time and pace are stored in this browser.')}</p>
    {failed && <p role="alert">{t('Could not access reading statistics.')}</p>}
    {sessions.length ? <>
      <dl className="discovery-stats reading-metrics">
        {metrics.map(([label, value]) => <div key={label}><dt>{t(label)}</dt><dd>{value}</dd></div>)}
      </dl>
      <p className="hint">{t('Reading for five minutes counts toward a streak.')}</p>
      <div className="reading-period">
        <label>{t('Activity period')} <select value={period} onChange={event => setPeriod(event.target.value as typeof period)}>
          <option value="week">{t('Last 7 days')}</option>
          <option value="month">{t('Last 30 days')}</option>
          <option value="year">{t('Last 12 months')}</option>
        </select></label>
      </div>
      <ActivityChart title={t('Reading activity')} t={t} duration={duration}
        buckets={stats[period].map(({date, seconds}) => ({label: dateLabel.format(date), seconds}))} />
      <details className="reading-patterns">
        <summary>{t('When you read')}</summary>
        <div>
          <ActivityChart title={t('By day of the week')} t={t} duration={duration}
            buckets={stats.weekdays.map((seconds, i) => ({label: weekdayLabel.format(Date.UTC(1970, 0, 5 + i)), seconds}))} />
          <ActivityChart title={t('By hour of the day')} t={t} duration={duration}
            buckets={stats.hours.map((seconds, i) => ({label: hourLabel.format(i * 3_600_000), seconds}))} />
        </div>
      </details>
      <details>
        <summary>{t('Time by book')}</summary>
        <dl className="reading-chart-values">
          {stats.books.map(([key, book]) => <div key={key}>
            <dt>{book.title}</dt>
            <dd>{duration(book.seconds)} · {interpolate(t, book.sessions === 1 ? '{count} session' : '{count} sessions', {count: book.sessions.toLocaleString(locale)})}</dd>
          </div>)}
        </dl>
      </details>
    </> : recording?.enabled && <p>{t('Open a book and read for at least 30 seconds to start seeing your activity.')}</p>}
    {recording && <div className="discovery-actions reading-recording">
      <label><input type="checkbox" checked={recording.enabled} disabled={busy}
        onChange={event => void update({enabled: event.target.checked})} /> {t('Record reading statistics')}</label>
      {sessions.length > 0 && <button className="link" disabled={busy} onClick={() => void clear()}>{t('Clear statistics')}</button>}
    </div>}
  </section>
}
