import { useEffect, useId, useState, type ReactNode } from 'react'
import {
  darkThemes, lightThemes, themeModes, themeNames,
  type ThemeChoice, type Theme,
} from './theme'

export type Translate = (label: string) => string

export const settingsSections = [
  { id: 'appearance', title: 'Appearance' },
  { id: 'reading', title: 'Reading' },
  { id: 'ai', title: 'AI features' },
  { id: 'catalogs', title: 'Catalogs' },
  { id: 'account', title: 'Account' },
  { id: 'storage', title: 'Storage and offline' },
  { id: 'about', title: 'About' },
] as const
export type SettingsSectionId = (typeof settingsSections)[number]['id']

/** The section an address names; `insights` is the AI tab's old address. */
export function sectionFor(id: string): SettingsSectionId | undefined {
  const name = id === 'insights' ? 'ai' : id
  return settingsSections.find((section) => section.id === name)?.id
}

/** The page: a title, a list of its sections, then each section that has something to show. */
export function SettingsPage({ t, focus, children }: {
  t: Translate
  /** The section the address names: it takes focus once it shows. */
  focus?: SettingsSectionId
  children: Partial<Record<SettingsSectionId, ReactNode>>
}) {
  const shown = settingsSections.filter(({ id }) => children[id])
  const present = shown.some(({ id }) => id === focus)
  useEffect(() => {
    if (focus && present) document.getElementById('settings-' + focus)?.focus()
  }, [focus, present])
  return (
    <div className="settings">
      <h1>{t('Settings')}</h1>
      <nav className="settings-index" aria-label={t('Settings sections')}>
        <ul>
          {shown.map(({ id, title }) => (
            <li key={id}><a href={'#/settings/' + id}>{t(title)}</a></li>
          ))}
        </ul>
      </nav>
      {shown.map(({ id, title }) => (
        <section key={id} id={'settings-' + id} className="settings-section" aria-labelledby={'settings-' + id + '-title'} tabIndex={-1}>
          <h2 id={'settings-' + id + '-title'}>{t(title)}</h2>
          <div className="panel">{children[id]}</div>
        </section>
      ))}
    </div>
  )
}

type Ids = { id: string; describedBy?: string }

/** One setting: its label and what it does on one side, its control on the other, wrapping under on a narrow screen. */
export function Setting({ label, description, stacked, action, children }: {
  label: string
  description?: ReactNode
  /** The control takes the full width under the text. */
  stacked?: boolean
  /** The control is a button, named by its own text: the label only titles the row. */
  action?: boolean
  children: (ids: Ids) => ReactNode
}) {
  const id = useId()
  const describedBy = description ? id + '-description' : undefined
  return (
    <div className={'setting' + (stacked ? ' stacked' : '')}>
      <div className="setting-text">
        {action ? <span className="setting-label">{label}</span> : <label className="setting-label" htmlFor={id}>{label}</label>}
        {description && <p className="hint" id={describedBy}>{description}</p>}
      </div>
      <div className="setting-control">{children({ id, describedBy })}</div>
    </div>
  )
}

/** A setting whose control is a group of radio buttons. */
export function ChoiceGroup({ legend, description, disabled, className, children }: {
  legend: string
  description?: ReactNode
  disabled?: boolean
  className: string
  children: ReactNode
}) {
  const id = useId()
  return (
    <fieldset className="setting stacked" disabled={disabled} aria-describedby={description ? id : undefined}>
      <legend className="setting-label">{legend}</legend>
      {description && <p className="hint" id={id}>{description}</p>}
      <div className={className}>{children}</div>
    </fieldset>
  )
}

/** A text and its on and off switch. */
export function SwitchSetting({ label, description, checked, disabled, onChange }: {
  label: string
  description?: ReactNode
  checked: boolean
  disabled?: boolean
  onChange: (on: boolean) => void
}) {
  return (
    <Setting label={label} description={description}>
      {({ id, describedBy }) => (
        <input
          id={id}
          type="checkbox"
          role="switch"
          className="switch"
          aria-describedby={describedBy}
          checked={checked}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
        />
      )}
    </Setting>
  )
}

const modeNames = { system: 'System', light: 'Light', dark: 'Dark' }

/** Mode, then the light and the dark palette, each shown as a small page of itself. */
export function ThemePicker({ choice, onChange, t }: {
  choice: ThemeChoice
  onChange: (choice: ThemeChoice) => void
  t: Translate
}) {
  const option = (theme: Theme, group: 'light' | 'dark') => (
    <label key={theme} className="theme-option">
      <span className="theme-page" data-preview={theme} aria-hidden="true">
        <span className="theme-page-title">Aa</span>
        <span className="theme-page-line" />
        <span className="theme-page-line short" />
        <span className="theme-page-button" />
      </span>
      <span className="choice">
        <input
          type="radio"
          name={'theme-' + group}
          value={theme}
          checked={choice[group] === theme}
          onChange={() => onChange({ ...choice, [group]: theme })}
        />
        {t(themeNames[theme])}
      </span>
    </label>
  )
  return (
    <>
      <ChoiceGroup
        legend={t('Mode')}
        description={t('System uses your light or your dark theme, following your device’s setting.')}
        className="choices"
      >
        {themeModes.map((mode) => (
          <label key={mode} className="choice boxed">
            <input
              type="radio"
              name="theme-mode"
              value={mode}
              checked={choice.mode === mode}
              onChange={() => onChange({ ...choice, mode })}
            />
            {t(modeNames[mode])}
          </label>
        ))}
      </ChoiceGroup>
      <ChoiceGroup legend={t('Light theme')} description={t('Used when Mode is System or Light.')}
        disabled={choice.mode === 'dark'} className="theme-options">
        {lightThemes.map((theme) => option(theme, 'light'))}
      </ChoiceGroup>
      <ChoiceGroup legend={t('Dark theme')} description={t('Used when Mode is System or Dark.')}
        disabled={choice.mode === 'light'} className="theme-options">
        {darkThemes.map((theme) => option(theme, 'dark'))}
      </ChoiceGroup>
    </>
  )
}

/** The plain background or the ink artwork, each shown as it looks. */
export function BackgroundPicker({ artwork, onChange, t }: {
  artwork: boolean
  onChange: (artwork: boolean) => void
  t: Translate
}) {
  return (
    <ChoiceGroup
      legend={t('Background')}
      description={t('Choose a background independently of your colour theme. Reading pages stay unchanged.')}
      className="theme-options"
    >
      {[false, true].map((art) => (
        <label key={String(art)} className="theme-option">
          <span className={'background-preview' + (art ? ' artwork' : '')} aria-hidden="true" />
          <span className="choice">
            <input type="radio" name="background" checked={artwork === art} onChange={() => onChange(art)} />
            {t(art ? 'Artwork' : 'Plain')}
          </span>
        </label>
      ))}
    </ChoiceGroup>
  )
}

/** The build this page runs, as the offline build records it. */
export function useVersion() {
  const [version, setVersion] = useState('')
  useEffect(() => {
    let live = true
    fetch(import.meta.env.BASE_URL + 'version.json', { cache: 'no-store' })
      .then((response) => (response.ok ? response.json() : undefined))
      .then((body: { version?: unknown } | undefined) => {
        if (live && typeof body?.version === 'string') setVersion(body.version)
      })
      .catch(() => undefined)
    return () => { live = false }
  }, [])
  return version
}
