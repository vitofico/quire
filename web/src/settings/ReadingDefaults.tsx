import { useEffect, useState, type CSSProperties } from 'react'
import type { Replica } from '../data'
import {
  fonts, pageLayouts, palettes, sanitizePreferences, type Font, type Palette, type ReaderPreferences,
} from '../reader/preferences'
import { readerStrings } from '../reader/strings'
import { Setting, type Translate } from './Settings'

const fontNames: Record<Font, string> = {
  serif: 'Serif', sans: 'Sans serif', mono: 'Monospace', publisher: 'Publisher font',
}

/**
 * How books open: the same preference the reader's Appearance panel changes,
 * kept in this browser for this library, as Android keeps its reader defaults on the phone.
 */
export function ReadingDefaults({ store, unavailable, lang, t }: {
  store?: Replica
  /** The browser's storage could not open: there is nothing to wait for. */
  unavailable?: boolean
  lang: string
  t: Translate
}) {
  const reader = readerStrings(lang)
  const [prefs, setPrefs] = useState<ReaderPreferences>()
  const [failed, setFailed] = useState('')
  useEffect(() => {
    let live = true
    store?.getPreference<ReaderPreferences>('reader').then(
      (stored) => { if (live) setPrefs(sanitizePreferences(stored)) },
      () => { if (live) setFailed(reader('Could not load reader settings')) },
    )
    return () => { live = false }
  }, [store])
  if (unavailable && !store) return <p role="alert">{t('Storage is unavailable.')}</p>
  if (failed && !prefs) return <p role="alert">{failed}</p>
  if (!store || !prefs) return <p className="hint" aria-busy="true">{t('Loading…')}</p>

  function change(patch: Partial<ReaderPreferences>) {
    const previous = prefs!
    const next = { ...previous, ...patch }
    setPrefs(next)
    setFailed('')
    store!.setPreference('reader', next).catch(() => {
      setPrefs(previous)
      setFailed(reader('Could not save reading preferences'))
    })
  }
  const range = (key: 'size' | 'spacing' | 'margin', label: string, min: number, max: number, step: number, shown: string) => (
    <Setting label={reader(label)}>
      {({ id, describedBy }) => (
        <span className="range">
          <input
            id={id} type="range" min={min} max={max} step={step} value={prefs[key]}
            aria-describedby={describedBy} aria-valuetext={shown}
            onChange={(event) => change({ [key]: +event.target.value })}
          />
          <output htmlFor={id}>{shown}</output>
        </span>
      )}
    </Setting>
  )
  const palette = palettes[prefs.palette]
  return (
    <>
      <p className="hint">{t('How books open in this browser. You can also change these while reading, under Appearance.')}</p>
      <div
        className="reading-sample"
        aria-hidden="true"
        style={{
          background: palette.background, color: palette.text,
          font: `${prefs.size}px/${prefs.spacing} ${fonts[prefs.font]}`,
          paddingInline: `min(${prefs.margin}px, 8%)`,
        } as CSSProperties}
      >
        {t('A page of your book looks like this, with the font, size and spacing you choose.')}
      </div>
      <Setting label={reader('Page layout')} description={reader('Automatic layout shows two pages when there is room.')}>
        {({ id, describedBy }) => (
          <select id={id} aria-describedby={describedBy} value={prefs.layout}
            onChange={(event) => change({ layout: event.target.value as ReaderPreferences['layout'] })}>
            {Object.entries(pageLayouts).map(([value, label]) => <option key={value} value={value}>{reader(label)}</option>)}
          </select>
        )}
      </Setting>
      <Setting label={reader('Palette')}>
        {({ id, describedBy }) => (
          <select id={id} aria-describedby={describedBy} value={prefs.palette}
            onChange={(event) => change({ palette: event.target.value as Palette })}>
            {(Object.keys(palettes) as Palette[]).map((name) => (
              <option key={name} value={name}>{reader(name[0].toUpperCase() + name.slice(1))}</option>
            ))}
          </select>
        )}
      </Setting>
      <Setting label={reader('Font')}>
        {({ id, describedBy }) => (
          <select id={id} aria-describedby={describedBy} value={prefs.font}
            onChange={(event) => change({ font: event.target.value as Font })}>
            {(Object.keys(fontNames) as Font[]).map((name) => (
              <option key={name} value={name}>{reader(fontNames[name])}</option>
            ))}
          </select>
        )}
      </Setting>
      {range('size', 'Text size', 14, 36, 1, prefs.size + ' px')}
      {range('spacing', 'Line spacing', 1.2, 2.4, 0.1, prefs.spacing.toFixed(1))}
      {range('margin', 'Margins', 12, 64, 1, prefs.margin + ' px')}
      {failed && <p role="alert">{failed}</p>}
    </>
  )
}
